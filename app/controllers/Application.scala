package controllers

/*
 * Play application controllers
 */

import java.io.File


import play.api._
import play.api.mvc._
import play.api.data._
import play.api.data.Forms._
import play.api.data.format.Formats._
import play.api.libs.concurrent._
import play.api.libs.concurrent.Execution.Implicits._
import play.api.libs.json.{Json,JsValue,JsObject,JsString,JsArray,JsNumber}
import play.api.Play.current
import play.api.db.DB

import scala.slick.driver.H2Driver.simple._
import Database.threadLocalSession
import org.panda.models._
import org.panda.models.Models.currentTimestamp

import org.panda.{AwsUploadManager,AwsUploadStatusManager,BeginUpload}

object Application extends Controller {
    // Get a lazy handle to the data base
    lazy val database = Database.forDataSource(DB.getDataSource())

    // Crude unique user ID generator
    private var idSeq = 0
    def uniqueUserId = {idSeq += 1; "user"+idSeq.toString}

    /*
     * Home page handler
     */

    def index = Action { implicit request =>
        play.api.Logger.info("index")
        Ok(views.html.upload(uniqueUserId))
    }

    /*
     * File upload handler
     */

    // File upload POST form
    case class FileUpload (
        description: String,
        file_n: Int,
        userid: String
    )
    val fileUploadForm = Form(
        mapping(
            "description" -> of[String],
            "file_n" -> of[Int],
            "userid" -> of[String]
        )(FileUpload.apply)(FileUpload.unapply)
    )

    def uploadToServer = Action(parse.multipartFormData) { implicit request =>
        play.api.Logger.info("uploadToServer")

        // Parse the form
        val formOpt : Option[FileUpload] = fileUploadForm.bindFromRequest().fold(
            errFrm => None,
            spec => Some(spec)
        )

        // Process the file
        request.body.file("thefile").map{ thefile =>
            val filename = thefile.filename
            val contenttype = thefile.contentType.get
            formOpt.map{ form =>
                // TODO - parameterize the bucket name. For now, just hard code it.
                val bucket = "mybucket.kevin01"
                val key = filename

                database withSession {
                    // Create a place holder record to track the status of file upload progress
                    val currentTS = currentTimestamp
                    val uploadProg = FileUploadProgress(None, form.userid, bucket, None, filename, contenttype, None, currentTS, currentTS,"pending")
                    val id = FileUploadProgressT.* returning FileUploadProgressT.id insert uploadProg
                    val q = Query(FileUploadProgressT).filter(_.id === id)

                    // Build a unique filename for the asset using the unique id from the tracking record
                    val uniqueFN = "%s-%d".format(filename,id)

                    // Construct a key
                    val key = "%s/%s/%s".format("asset",form.userid,uniqueFN)

                    // Construct a temporary filename for local storage
                    val tmpFilename = "/tmp/%s-%s-%s".format(form.userid,bucket,uniqueFN)

                    // Update the status record
                    q.map(r => r.key ~ r.tmpFilename).update( key, tmpFilename )

                    // Move the uploaded file to local storage
                    val f = new File(tmpFilename)
                    thefile.ref.moveTo(f,true)

                    // Update the status record
                    q.map(r => r.status ~ r.updateTS).update("copied to server", currentTimestamp)

                    // Tell the AWS uploader to begin uploading
                    AwsUploadManager.myActor ! BeginUpload(f,id)
                }

                // Construct the JSON response message
                val rtn = Json.obj(
                    "okay" -> true,
                    "desc" -> form.description,
                    "file_n" -> form.file_n,
                    "ctype" -> contenttype,
                    "userid" -> form.userid
                )
                Ok(rtn)
            }.getOrElse {
                BadRequest("Form binding error.")
            }
        }.getOrElse {
            BadRequest("File not attached.")
        }
    }

    /*
     * Status update registration handler
     */

    def registerForStatusUpdates (userId: String) = WebSocket.async[JsValue] { request =>
        AwsUploadStatusManager.registerForStatusUpdates(userId)
    }

}
