package org.panda.models

import scala.slick.driver.H2Driver.simple._

case class FileUploadProgress (
    id: Option[Int],
    userId: String,
    bucket: String,
    key: Option[String],
    filename: String,
    contentType: String,
    tmpFilename: Option[String],
    createTS: java.sql.Timestamp,
    updateTS: java.sql.Timestamp,
    status: String
    )
{
    override def toString : String = "<%d %s %s %s>".format(id.getOrElse(-1),userId,bucket,key.getOrElse("no-key"))
}

case class TranscodeProgress (
    id: Option[Int],
    userId: String,
    bucket: String,
    key: String,
    presetId: String,
    contentType: String,
    //thumb: Option[String],
    createTS: java.sql.Timestamp,
    updateTS: java.sql.Timestamp,
    status: String,
    jobId: Option[String]
    )
{

}

object FileUploadProgressT extends Table[FileUploadProgress]("file_upload_process") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[String]("user_id")
    def bucket = column[String]("bucket", O.Nullable)
    def key = column[String]("key", O.Nullable)
    def filename = column[String]("filename")
    def contentType = column[String]("content_type")
    def tmpFilename = column[String]("tmp_filename", O.Nullable)
    def createTS = column[java.sql.Timestamp]("create_ts")
    def updateTS = column[java.sql.Timestamp]("update_ts")
    def status = column[String]("status")

    def * = id.? ~ userId ~ bucket ~ key.? ~ filename ~ contentType ~ tmpFilename.? ~ createTS ~ updateTS ~ status <> (FileUploadProgress, FileUploadProgress.unapply _)
}

object TranscodeProgressT extends Table[TranscodeProgress]("transcode_progress") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def userId = column[String]("user_id")
    def bucket = column[String]("bucket")
    def key = column[String]("key")
    def presetId = column[String]("preset_id")
    def contentType = column[String]("content_type")
    def createTS = column[java.sql.Timestamp]("create_ts")
    def updateTS = column[java.sql.Timestamp]("update_ts")
    def status = column[String]("status")
    def jobId = column[String]("job_id", O.Nullable)

    def * = id.? ~ userId ~ bucket ~ key ~ presetId ~ contentType ~ createTS ~ updateTS ~ status ~ jobId.? <> (TranscodeProgress, TranscodeProgress.unapply _)
}

object Models {
    def currentTimestamp () = {
        val d = new java.util.Date()
        new java.sql.Timestamp(d.getTime)
    }
}
