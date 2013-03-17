package org.panda

import java.io.File

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

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession
import play.api.db.DB
import org.panda.models._
import org.panda.models.Models.currentTimestamp

import collection.JavaConversions._
import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider
import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest,GetQueueUrlRequest}

// Amazon Elastic Transcoding message structure
case class AwsElasticTranscodeMsg (
    state: String,
    jobId: String,
    pipelineId: String,
    errorCode: Option[String],
    messageDetails: Option[String])

// Amazon Simple Queuing Service (SQS) message structure
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

/*
 * AwsSQSEventSource Actor
 *
 * This actor monitors the SQS for transcoding completion messages. It uses a rather
 * crude polling approach that would be better handled with a direct event message
 * from the Elastic Transcoder (ET) processing pipe. This should be easy to do,
 * but would require this app to provide a publicly accessible URL for the Amazon
 * service to send to. That would have added more complexity to the simple purpose
 * of this exercise - so I cheated and had the ET pipe notificates sent to an
 * Amazon Simple Notification Service (SNS) Topic, created an Amazon Simple
 * Queuing Service (SQS) subscribed to that Topic, and wait/poll for message to arrive
 * on the SQS in the Actor below.
 */

case class WaitForAWSEvent ()

class AwsSQSEventSource extends Actor {
    println("AwsSQSEventSource")

    // Amazon SQS service handle
    val sqs = new AmazonSQSClient(new ClasspathPropertiesFileCredentialsProvider())

    // Construct the query message
    // TODO - the queue name is hard coded
    val getQueueUrlRequest = new GetQueueUrlRequest("kevin-transcoding-progress-queue")
    val queueUrl = sqs.getQueueUrl(getQueueUrlRequest).getQueueUrl
    val receiveMessageRequest = new ReceiveMessageRequest(queueUrl)
    receiveMessageRequest.setWaitTimeSeconds(10)

    implicit val aswMsg = Json.reads[AswSqsMsg]

    def receive = {
        case WaitForAWSEvent => {
            println("WaitForAWSEvent")

            // Wait for a message to arrive. We might get a message, or we might time out.
            val r = sqs.receiveMessage(receiveMessageRequest)

            // Process any received messages
            for (msg <- r.getMessages) {
                val msgBody = msg.getBody
                val jsval = Json.parse(msgBody)

                jsval.asOpt[AswSqsMsg] match {
                    // We received a message, send it to AwsTranscodeManager
                    case Some(m) => AwsTranscodeManager.myActor ! m

                    // Error trap
                    case _ =>  {
                        println("Problem unpacking SQS message")
                        println(msgBody)
                    }
                }
            }

            // Do this again (forever)
            self ! WaitForAWSEvent
        }
    }
}

object AwsSQSEventSource {
    println("object AwsSQSEventSource")

    val myActor = Akka.system.actorOf(Props[AwsSQSEventSource])
}

/*
 * AwsUploadStatusManager Actor
 *
 * This actor is responsible for sending status update event message back
 * to the client.
 */

case class Join(username: String)
case class StatusUpdate (id: Int)
case class TranscodeStatusUpdate (id: Int)
case class StatusUpdateAll ()

class AwsUploadStatusManager extends Actor {
    println("AwsUploadStatusManager")

    lazy val database = Database.forDataSource(DB.getDataSource())
    var statusUpdateMember = Map.empty[String, Concurrent.Channel[JsValue]]

    def receive = {
        // Register a new client
        case Join(userId) => {
            val (chatEnumerator, chatChannel) = Concurrent.broadcast[JsValue]
            statusUpdateMember = statusUpdateMember + (userId -> chatChannel)
            sender ! Connected(chatEnumerator)
        }

        // Send the client a file upload status update message
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

        // Send the client a transcode status update message
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

        // Doesn't do anything useful right now
        case StatusUpdateAll => {
            println("StatusUpdateAll")
        }
    }
}

case class Connected(enumerator: Enumerator[JsValue])

object AwsUploadStatusManager {
    println("object AwsUploadStatusManager")

    val myActor = Akka.system.actorOf(Props[AwsUploadStatusManager])
    implicit val timeout = Timeout(1 second)

    // Doesn't do anything useful right now, just pings itself every 10 seconds
    Akka.system.scheduler.schedule(
        30 seconds,
        10 seconds,
        myActor,
        StatusUpdateAll
    )

    // Register a new client. Returns a WebSocket connection
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

/*
 * AwsUploadManager Actor
 *
 * This actor is responsible for the Amazon file upload.
 */

case class BeginUpload (f: File, id: Int)
case class FinishedUpload (id: Int)

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

/*
 * AwsTranscodeManager Actor
 *
 * This actor is responsible for the Amazon Elastic Transcoding process.
 */

case class BeingTranscodeVideo (id: Int)
case class FinishedTranscodeVideo (id: Int)

class AwsTranscodeManager extends Actor {
    println("AwsTranscodeManager")

    lazy val database = Database.forDataSource(DB.getDataSource())

    def receive = {
        case m: AswSqsMsg => {
            println("AwsMsg = %s".format(m))
            val m4 = m.getMessage
            println("AwsElasticTranscodeMsg = %s".format(m4))

            /*
             * TODO - correlate complete message with the appropriate
             * TranscodeProgress table entry.
             */
        }

        case BeingTranscodeVideo(id) => {
            println("BeingTranscodeVideo")
            database withSession {
                // Update the FileUploadProgressT status, we'll use it as a group head
                val q1 = Query(FileUploadProgressT).filter(_.id === id)
                q1.map(r => r.status ~ r.updateTS).update("transcoding assets", currentTimestamp)
                val uProg = q1.first

                // Create a TranscodeProgressT record for this task
                val currentTS = currentTimestamp
                val presetId = "1351620000000-000040"
                val oKey =  "trans/%s/%s-{id}".format(uProg.userId,uProg.filename)
                val transcodeProg = TranscodeProgress(None, uProg.userId, uProg.bucket, oKey, presetId, uProg.contentType, currentTS, currentTS, "transcode pending", None)
                val id2 = TranscodeProgressT.* returning TranscodeProgressT.id insert transcodeProg

                // Send a status update to the client
                AwsUploadStatusManager.myActor ! TranscodeStatusUpdate(id2)

                // Submit the transcoding job
                val job = Aws.submitVideoTranscodeRequest(
                    iKey = uProg.key.get,
                    oKey = oKey.replaceAll("""\{id\}""",id2.toString),
                    thumbPatt = "trans/%s/%s-%s-{resolution}-{count}".format(uProg.userId,uProg.filename,id2),
                    presetId = presetId)

                // Update the TranscodeProgressT record
                val q2 = Query(TranscodeProgressT).filter(_.id === id2)
                q2.map(r => r.status ~ r.updateTS ~ r.jobId).update("submitted", currentTimestamp, job.getId)

                // Send a status update to the client
                AwsUploadStatusManager.myActor ! TranscodeStatusUpdate(id2)
            }
        }

        case FinishedTranscodeVideo(id) => {
            // TODO - send a transcoding status update to the client
        }

    }
}

object AwsTranscodeManager {
    println("object AwsTranscodeManager")

    val myActor = Akka.system.actorOf(Props[AwsTranscodeManager])

    AwsSQSEventSource.myActor ! WaitForAWSEvent
}
