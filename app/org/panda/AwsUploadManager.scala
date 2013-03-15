package org.panda

//import play.api._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.iteratee._
import play.api.libs.json.{Json,JsValue,JsObject,JsString,JsArray,JsNumber}
import play.api.Play.current

import akka.actor._
import akka.util.Timeout
import akka.pattern.ask
import akka.routing.RoundRobinRouter

import scala.concurrent.duration._
import scala.concurrent.Future

import java.io.File

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession
import play.api.db.DB
import org.panda.models._
import org.panda.models.Models.currentTimestamp

import collection.JavaConversions._
import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest,GetQueueUrlRequest}

case class Join(username: String)
case class StatusUpdate (id: Int)
case class TranscodeStatusUpdate (id: Int)
case class StatusUpdateAll ()
case class Connected(enumerator: Enumerator[JsValue])
case class CannotConnect(msg: String)

case class BeginUpload (f: File, id: Int)
case class FinishedUpload (id: Int)

case class AWSEvent ()
case class WaitForAWSEvent ()

case class BeingTranscodeVideo (id: Int)
case class FinishedTranscodeVideo (id: Int)

case class AwsElasticTranscodeMsg (
    state: String,
    jobId: String,
    pipelineId: String,
    errorCode: Option[String],
    messageDetails: Option[String])

case class AswSqsMsg (
    Type: String,
    MessageId: String,
    TopicArn: String,
    Subject: String,
    Message: String,
    Timestamp: String)
{
    def getMessage () : Option[AwsElasticTranscodeMsg] = {
        val jsval = Json.parse(Message)
        jsval.asOpt[AwsElasticTranscodeMsg](Json.reads[AwsElasticTranscodeMsg])
    }
}

class AwsSQSEventSource extends Actor {
    println("AwsSQSEventSource")
    val sqs = new AmazonSQSClient(new ClasspathPropertiesFileCredentialsProvider())
    val getQueueUrlRequest = new GetQueueUrlRequest("lyynks-transcoding-progress-queue")
    val queueUrl = sqs.getQueueUrl(getQueueUrlRequest).getQueueUrl
    val receiveMessageRequest = new ReceiveMessageRequest(queueUrl)
    receiveMessageRequest.setWaitTimeSeconds(10)

    implicit val aswMsg = Json.reads[AswSqsMsg]

    def receive = {
        case AWSEvent => {

        }

        case WaitForAWSEvent => {
            println("WaitForAWSEvent")
            val r2 = sqs.receiveMessage(receiveMessageRequest)
            for (msg <- r2.getMessages) {
                val msgBody = msg.getBody
                val jsval = Json.parse(msgBody)

                jsval.asOpt[AswSqsMsg] match {
                    case Some(m) => AwsTranscodeManager.myActor ! m
                    case _ =>  {
                        println("Problem unpacking SQS message")
                        println(msgBody)
                    }
                }

            }
            self ! WaitForAWSEvent
        }
    }
}

object AwsSQSEventSource {
    println("object AwsSQSEventSource")
    val myActor = Akka.system.actorOf(Props[AwsSQSEventSource])
}

class AwsUploadStatusManager extends Actor {
    println("AwsUploadStatusManager")
    lazy val database = Database.forDataSource(DB.getDataSource())
    var statusUpdateMember = Map.empty[String, Concurrent.Channel[JsValue]]

    def receive = {
        case Join(userId) => {
            val (chatEnumerator, chatChannel) = Concurrent.broadcast[JsValue]
            statusUpdateMember = statusUpdateMember + (userId -> chatChannel)
            sender ! Connected(chatEnumerator)
        }

        case StatusUpdate(id) => {
            database withSession {
                val q1 = Query(FileUploadProgressT).filter(_.id === id)
                val uProg = q1.first
                val msg = Json.obj(
                    "msgType" -> "upload_status",
                    "id" -> uProg.id.get,
                    "userId" -> uProg.userId,
                    "filename" -> uProg.filename,
                    "contentType" -> uProg.contentType,
                    "createTS" -> uProg.createTS.getTime,
                    "updateTS" -> uProg.updateTS.getTime,
                    "status" -> uProg.status
                )
                statusUpdateMember(uProg.userId).push(msg)
            }
        }

        case TranscodeStatusUpdate(id) => {
            database withSession {
                val q1 = Query(TranscodeProgressT).filter(_.id === id)
                val uProg = q1.first
                val msg = Json.obj(
                    "msgType" -> "transcode_status",
                    "id" -> uProg.id.get,
                    "userId" -> uProg.userId,
                    "contentType" -> uProg.contentType,
                    "createTS" -> uProg.createTS.getTime,
                    "updateTS" -> uProg.updateTS.getTime,
                    "status" -> uProg.status
                )
                statusUpdateMember(uProg.userId).push(msg)
            }
        }

        case StatusUpdateAll => {
            println("StatusUpdateAll")
        }
    }
}
object AwsUploadStatusManager {
    println("object AwsUploadStatusManager")
    val myActor = Akka.system.actorOf(Props[AwsUploadStatusManager])
    implicit val timeout = Timeout(1 second)

    Akka.system.scheduler.schedule(
        30 seconds,
        10 seconds,
        myActor,
        StatusUpdateAll
    )

    def registerForStatusUpdates (username: String) : Future[(Iteratee[JsValue,_],Enumerator[JsValue])] = {
        val x = myActor ? Join(username)
        x.map {
            case Connected(enumerator) => {
                val in = Iteratee.foreach[JsValue] { event =>
                    println(event)
                }.mapDone { _ =>
                    println("Disconnected")
                }
                (in,enumerator)
            }
        }
    }
}

class AwsUploadManager extends Actor {
    lazy val database = Database.forDataSource(DB.getDataSource())

    def receive = {
        case BeginUpload(f,id) => {
            println("BeginUpload")
            database withSession {
                val q1 = Query(FileUploadProgressT).filter(_.id === id)
                q1.map(r => r.status ~ r.updateTS).update("copying to S3", currentTimestamp)
                val uProg = q1.first
                println(uProg)
                AwsUploadStatusManager.myActor ! StatusUpdate(id)
                Aws.copyFile(uProg.bucket, uProg.key.get, f, uProg.contentType)
                q1.map(r => r.status ~ r.updateTS).update("copied to S3", currentTimestamp)
                AwsUploadStatusManager.myActor ! StatusUpdate(id)

                val Pattern = """^(\w+)/?.*$""".r
                uProg.contentType match {
                    case Pattern(contentType) => {
                        println("content type = %s".format(contentType))
                        contentType match {
                            // if this is a video, begin the transcoding process
                            case "video" => {
                                AwsTranscodeManager.myActor ! BeingTranscodeVideo(id)
                            }
                            case x => println("no match = ".format(x))
                        }

                    }
                    case x => println("no match = ".format(x))
                }
            }
            self ! FinishedUpload(id)

        }

        case FinishedUpload(id) => {
            database withSession {
                val uProg = Query(FileUploadProgressT).filter(_.id === id).first
                println("finished %s".format(uProg))
                val f = new File (uProg.tmpFilename.get)
                f.delete()
                println("file deleted %s".format(uProg.tmpFilename.get))
            }
            AwsUploadStatusManager.myActor ! StatusUpdate(id)
        }
    }
}

object AwsUploadManager {
    println("object AwsUploadManager")

    // Create a pool of actors
    val myActor = Akka.system.actorOf(Props[AwsUploadManager].withRouter(RoundRobinRouter(nrOfInstances = 4)))
}

class AwsTranscodeManager extends Actor {
    println("AwsTranscodeManager")
    lazy val database = Database.forDataSource(DB.getDataSource())

    def receive = {
        case m: AswSqsMsg => {
            println("AwsMsg = %s".format(m))
            val m4 = m.getMessage
            println("AwsElasticTranscodeMsg = %s".format(m4))

        }

        case BeingTranscodeVideo(id) => {
            println("BeingTranscodeVideo")
            database withSession {
                // Update the FileUploadProgressT status, we'll use it as a group head
                val q1 = Query(FileUploadProgressT).filter(_.id === id)
                q1.map(r => r.status ~ r.updateTS).update("transcoding assets", currentTimestamp)
                val uProg = q1.first

                val currentTS = currentTimestamp
                val presetId = "1351620000000-000040"
                val oKey =  "trans/%s/%s-{id}".format(uProg.userId,uProg.filename)
                val transcodeProg = TranscodeProgress(None, uProg.userId, uProg.bucket, oKey, presetId, uProg.contentType, currentTS, currentTS, "transcode pending", None)
                val id2 = TranscodeProgressT.* returning TranscodeProgressT.id insert transcodeProg
                AwsUploadStatusManager.myActor ! TranscodeStatusUpdate(id2)

                val job1 = Aws.submitVideoTranscodeRequest(
                    iKey = uProg.key.get,
                    oKey = oKey.replaceAll("""\{id\}""",id2.toString),
                    thumbPatt = "trans/%s/%s-%s-{resolution}-{count}".format(uProg.userId,uProg.filename,id2),
                    presetId = presetId)

                val q2 = Query(TranscodeProgressT).filter(_.id === id2)
                q2.map(r => r.status ~ r.updateTS ~ r.jobId).update("submitted", currentTimestamp, job1.getId)
            }
        }

        case FinishedTranscodeVideo(id) => {

        }

    }
}

object AwsTranscodeManager {
    println("object AwsTranscodeManager")
    val myActor = Akka.system.actorOf(Props[AwsTranscodeManager])

    AwsSQSEventSource.myActor ! WaitForAWSEvent
}
