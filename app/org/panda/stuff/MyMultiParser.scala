package org.panda.stuff

import play.api.mvc.MultipartFormData
import play.api.mvc.MultipartFormData.FilePart
import play.api.mvc.BodyParser
import play.api.mvc.BodyParsers.parse.multipartFormData
import play.api.mvc.BodyParsers.parse.Multipart.{FileInfo,PartHandler,handleFilePart}
import play.api.libs.iteratee._
import collection.JavaConversions._
import com.amazonaws.services.s3.{AmazonS3,AmazonS3Client}
import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider
import com.amazonaws.services.s3.model.{PutObjectRequest, CannedAccessControlList}
import com.amazonaws.services.s3.model.ObjectMetadata
import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.model.ProgressEvent
import com.amazonaws.services.s3.model.ProgressListener
import java.io.{PipedInputStream,PipedOutputStream}

object MyMultiParser {
    private val s3: AmazonS3 = new AmazonS3Client(new ClasspathPropertiesFileCredentialsProvider())

    case class MyS3File (bucket: String, filename: String) {}

    def handleFilePartAsS3File: PartHandler[FilePart[MyS3File]] = {
        handleFilePart {
            case FileInfo(partName, filename, contentType) =>
                val bucketName = "lyynks-whitelabel.kevin01"
                val x = MyS3File(bucketName, filename)
                val s = new PipedInputStream
                val m = new ObjectMetadata()
                val r = new PutObjectRequest(bucketName, filename, s, m)
                r.setCannedAcl(CannedAccessControlList.PublicRead)

                val tx = new TransferManager(new ClasspathPropertiesFileCredentialsProvider().getCredentials());
                val upload = tx.upload(r)

                val progressListener: ProgressListener = new ProgressListener() {
                    def progressChanged(progressEvent: ProgressEvent) {
                        val x = upload.getProgress().getBytesTransfered()
                        println("number of bytes transfered %d".format(x))
                        progressEvent.getEventCode match {
                            case ProgressEvent.COMPLETED_EVENT_CODE => println("upload complete")
                            case ProgressEvent.FAILED_EVENT_CODE => println("upload failed")
                        }
                    }
                }
                upload.addProgressListener(progressListener)

                Iteratee.fold[Array[Byte], PipedOutputStream](new PipedOutputStream(s)) { (os, data) =>
                    os.write(data)
                    os
                }.mapDone { os =>
                    os.flush
                    os.close
                    x
                }
        }
    }

    def multipartFormDataX: BodyParser[MultipartFormData[MyS3File]] = multipartFormData(handleFilePartAsS3File)

}
