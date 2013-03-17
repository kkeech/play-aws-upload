package org.panda

/*
 * Some Amazon services utility functions
 */

import java.io.File

import collection.JavaConversions._

import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException

import com.amazonaws.services.s3.{AmazonS3,AmazonS3Client}
import com.amazonaws.services.s3.model.{Bucket,ObjectListing,ListObjectsRequest,PutObjectRequest,CannedAccessControlList,ObjectMetadata}

import com.amazonaws.services.s3.transfer.{TransferManager,Upload}
import com.amazonaws.services.s3.model.{ProgressEvent,ProgressListener}

import com.amazonaws.services.elastictranscoder.{AmazonElasticTranscoder,AmazonElasticTranscoderClient}
import com.amazonaws.services.elastictranscoder.model.{JobInput,JobOutput,CreateJobOutput,CreateJobRequest,ReadJobRequest,Job}

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest,GetQueueUrlRequest}


object Aws {
    // Amazon S3 service handle
    private val s3: AmazonS3 = new AmazonS3Client(new ClasspathPropertiesFileCredentialsProvider())
    
    // Amazon Elastic Transcoder service handle
    private val eT : AmazonElasticTranscoder  = new AmazonElasticTranscoderClient(new ClasspathPropertiesFileCredentialsProvider())

    // Copy the file in the forground. Returns when upload is complete.
    def copyFile (bucketName: String, key: String, f: File, contentType: String) {
        // Construct the request
        val r = new PutObjectRequest(bucketName, key, f)
        r.setCannedAcl(CannedAccessControlList.PublicRead)
        val m = new ObjectMetadata()
        m.setContentType(contentType)
        r.setMetadata(m)
        
        try {
            s3.createBucket(bucketName)
            // Copy the file to S3
            val x = s3.putObject(r)
        } catch {
            case cExc: AmazonClientException => println(cExc.toString())
            case sExc: AmazonServiceException => println(sExc.toString())
        }
    }
    
    // Copy the file in the background. Returns immediately.
    def copyFileWithProgress (bucketName: String, key: String, f: File, contentType: String) {
        // Construct the request
        val r = new PutObjectRequest(bucketName, key, f)
        r.setCannedAcl(CannedAccessControlList.PublicRead)
        val m = new ObjectMetadata()
        m.setContentType(contentType)
        r.setMetadata(m)
        
        val tx = new TransferManager(new ClasspathPropertiesFileCredentialsProvider().getCredentials());
        
        // Begin the async upload of the file to S3
        val upload = tx.upload(r)

        // Attach a progress handler to the async upload process
        val progressListener: ProgressListener = new ProgressListener() {
            def progressChanged(progressEvent: ProgressEvent) {
                val x = upload.getProgress().getBytesTransfered()
                println("number of bytes transfered %d".format(x))
                progressEvent.getEventCode match {
                    // TODO - do something useful with the upload complete event
                    case ProgressEvent.COMPLETED_EVENT_CODE => println("upload complete")
                    case ProgressEvent.FAILED_EVENT_CODE => println("upload failed")
                }
            }
        }
        upload.addProgressListener(progressListener)
    }

    // Submit a video transcoding task to the Elastic Transcoder
    def submitVideoTranscodeRequest (
        iKey: String,
        oKey: String,
        thumbPatt: String,
        presetId: String
    ) : Job = {
        val jobIn = new JobInput
        jobIn.setKey(iKey)
        jobIn.setAspectRatio("auto")
        jobIn.setContainer("auto")
        jobIn.setFrameRate("auto")
        jobIn.setInterlaced("auto")
        jobIn.setResolution("auto")

        val jobOut = new CreateJobOutput
        jobOut.setKey(oKey)
        jobOut.setThumbnailPattern(thumbPatt)
        jobOut.setPresetId(presetId)
        jobOut.setRotate("auto")

        val jobRequest = new CreateJobRequest
        jobRequest.setInput(jobIn)
        jobRequest.setOutput(jobOut)
        // TODO - the pipeline ID is hard coded.
        jobRequest.setPipelineId("1361383070787-0691e8")

        // This submits the job, but how do I track the progress?
        val job = eT.createJob(jobRequest).getJob()
        println(job)

        job
    }

}
