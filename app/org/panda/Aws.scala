package org.panda

import collection.JavaConversions._

import com.amazonaws.auth.ClasspathPropertiesFileCredentialsProvider
import com.amazonaws.AmazonClientException
import com.amazonaws.AmazonServiceException

import com.amazonaws.services.s3.{AmazonS3,AmazonS3Client}
import com.amazonaws.services.s3.model.{Bucket,ObjectListing,ListObjectsRequest,PutObjectRequest,CannedAccessControlList}
import com.amazonaws.services.s3.model.ObjectMetadata

import com.amazonaws.services.s3.transfer.TransferManager
import com.amazonaws.services.s3.model.ProgressEvent
import com.amazonaws.services.s3.model.ProgressListener
import com.amazonaws.services.s3.transfer.Upload

import com.amazonaws.services.elastictranscoder.{AmazonElasticTranscoder,AmazonElasticTranscoderClient}
import com.amazonaws.services.elastictranscoder.model.{JobInput,JobOutput,CreateJobOutput,CreateJobRequest,ReadJobRequest,Job}

import com.amazonaws.services.sqs.AmazonSQSClient
import com.amazonaws.services.sqs.model.{ReceiveMessageRequest,GetQueueUrlRequest}

import java.io.File

object Aws {
    private val s3: AmazonS3 = new AmazonS3Client(new ClasspathPropertiesFileCredentialsProvider())
    private val eT : AmazonElasticTranscoder  = new AmazonElasticTranscoderClient(new ClasspathPropertiesFileCredentialsProvider())

    def listBuckets () {
        for (b <- s3.listBuckets()) {
            println(b.getName())
        }

        val x = new ListObjectsRequest().withBucketName("lyynks-whitelabel.dug") //.withPrefix("My")
        val z = s3.listObjects(x)
        for (s <- z.getObjectSummaries()) {
            println("%20s %d %s".format(s.getKey,s.getSize,s.getLastModified().toString))
        }
    }

    def copyFile (bucketName: String, key: String, f: File, contentType: String) {
        val r = new PutObjectRequest(bucketName, key, f)
        r.setCannedAcl(CannedAccessControlList.PublicRead)
        val m = new ObjectMetadata()
        m.setContentType(contentType)
        r.setMetadata(m)
        try {
            s3.createBucket(bucketName)
            val x = s3.putObject(r)
        } catch {
            case cExc: AmazonClientException => println(cExc.toString())
            case sExc: AmazonServiceException => println(sExc.toString())
        }
    }

    def copyFileWithProgress (bucketName: String, key: String, f: File, contentType: String) {
        val r = new PutObjectRequest(bucketName, key, f)
        r.setCannedAcl(CannedAccessControlList.PublicRead)
        val m = new ObjectMetadata()
        m.setContentType(contentType)
        r.setMetadata(m)

        val tx = new TransferManager(new ClasspathPropertiesFileCredentialsProvider().getCredentials());
        val upload = tx.upload(r)

        val progressListener: ProgressListener = new ProgressListener() {
            def progressChanged(progressEvent: ProgressEvent) {
                //val n = progressEvent.getBytesTransfered()
                val x = upload.getProgress().getBytesTransfered()
                println("number of bytes transfered %d".format(x))
                progressEvent.getEventCode match {
                    case ProgressEvent.COMPLETED_EVENT_CODE => println("upload complete")
                    case ProgressEvent.FAILED_EVENT_CODE => println("upload failed")
                }
            }
        }
        upload.addProgressListener(progressListener)
    }

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
        jobRequest.setPipelineId("1361383070787-0691e8")

        // This submits the job, but how do I track the progress?
        val job = eT.createJob(jobRequest).getJob()
        println(job)

        //val jobRequest2 = new ReadJobRequest
        //jobRequest2.setId(job1.getJob.getId)

        //val jobRead1 = eT.readJob(jobRequest2)
        job
    }

    // S3Object object = s3.getObject(new GetObjectRequest(bucketName, key));

    // s3.deleteObject(bucketName, key);

    // s3.deleteBucket(bucketName);

}
