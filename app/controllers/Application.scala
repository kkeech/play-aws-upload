package controllers

import play.api._
import play.api.mvc._
import play.api.libs.json.{Json,JsValue,JsObject,JsString,JsArray,JsNumber}
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.Play.current
import play.api.libs.iteratee._
import java.io.File

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession
import play.api.db.DB
import org.panda.models._
import org.panda.models.Models.currentTimestamp

import org.panda.{AwsUploadManager,AwsUploadStatusManager,BeginUpload}
import org.panda.stuff.{MyMultiParser,LiveUpdate}

object Application extends Controller {
    private var idSeq1 = 0
    private var idSeq2 = 0

    lazy val database = Database.forDataSource(DB.getDataSource())

    def uniqueUserId = {idSeq1 += 1; "user"+idSeq1.toString}

    def index = Action { implicit request =>
        Ok(views.html.upload(uniqueUserId))
    }

    case class FileUpload (
        description: String,
        xx_int: Int,
        userid: String
    )
    val fileUploadForm = Form(
        mapping(
            "description" -> of[String],
            "xx_int" -> of[Int],
            "userid" -> of[String]
        )(FileUpload.apply)(FileUpload.unapply)
    )

    def uploadToServer1 = Action(parse.multipartFormData) { implicit request =>
        play.api.Logger.info("uploadToServer1")
        val fs : Option[FileUpload] = fileUploadForm.bindFromRequest().fold(
            errFrm => None,
            spec => Some(spec)
        )

        request.body.file("thefile").map{ thefile =>
            val filename = thefile.filename
            val contenttype = thefile.contentType.get
            fs.map{ x =>
                val bucket = "lyynks-whitelabel.kevin01"
                val key = filename
                database withSession {
                    // Create a place holder record to track the status of file upload progress
                    val currentTS = currentTimestamp
                    val uploadProg = FileUploadProgress(None, x.userid, bucket, None, filename, contenttype, None, currentTS, currentTS,"pending")
                    val id = FileUploadProgressT.* returning FileUploadProgressT.id insert uploadProg
                    val q1 = Query(FileUploadProgressT).filter(_.id === id)

                    // Build a unique filename for the asset using the unique id from the tracking record
                    val uniqueFN = "%s-%d".format(filename,id)

                    // Construct a key
                    val key = "%s/%s/%s".format("asset",x.userid,uniqueFN)

                    // Construct a temporary filename for local storage
                    val tmpFilename = "/tmp/%s-%s-%s".format(x.userid,bucket,uniqueFN)

                    // Update the status record
                    q1.map(r => r.key ~ r.tmpFilename).update( key, tmpFilename )

                    // Move the uploaded file to local storage
                    val f = new File(tmpFilename)
                    thefile.ref.moveTo(f,true)

                    // Update the status record
                    q1.map(r => r.status ~ r.updateTS).update("copied to server", currentTimestamp)

                    AwsUploadManager.myActor ! BeginUpload(f,id)
                }
                val rtn = Json.obj(
                    "okay" -> true,
                    "desc" -> x.description,
                    "xx_int" -> x.xx_int,
                    "ctype" -> contenttype,
                    "userid" -> x.userid
                )
                Ok(rtn)
            }.getOrElse {
                BadRequest("Form binding error.")
            }
        }.getOrElse {
            BadRequest("File not attached.")
        }
    }

    def uploadToServer3 = Action(MyMultiParser.multipartFormDataX) { implicit request =>
        val fs : Option[FileUpload] = fileUploadForm.bindFromRequest().fold(
            errFrm => None,
            spec => Some(spec)
        )

        request.body.file("thefile").map{ thefile =>
            val filename = thefile.filename
            val contenttype = thefile.contentType.get
            fs.map{ x =>
                val rtn = Json.obj(
                    "okay" -> true,
                    "desc" -> x.description,
                    "xx_int" -> x.xx_int,
                    "ctype" -> contenttype,
                    "bucket" -> thefile.ref.bucket,
                    "filename" -> thefile.ref.filename
                )
                Ok(rtn)
            }.getOrElse {
                BadRequest("Form binding error.")
            }
        }.getOrElse {
            BadRequest("File not attached.")
        }
    }

    def liveUpdate (userId: String) = WebSocket.async[JsValue] { request  =>
        LiveUpdate.join(userId)
    }

    def statusUpdate (userId: String) = WebSocket.async[JsValue] { request =>
        AwsUploadStatusManager.registerForStatusUpdates(userId)
    }

}
