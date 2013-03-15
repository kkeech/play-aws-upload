play-aws-upload
===============

A Scala prototype project integrating multi-file drag and drop upload to Amazon storage
with background transcoding and progress feedback.

## Description
This is a prototype project, a proof of concept - I wanted to see what it took to implement
a multi-file upload with
cloud storage, simple transcoding, background processing, and WebSocket status updates. The client/server
connection is under load only during the that portion of the process where the file is transfered
from the client to the server. Instead of polling the server for status updates of the S3 copy and
transcoding process, event style WebSocket messages are sent to the client.
* multi-file upload, HTML5/JavaScript front end
* client side event driven status updates using WebSocket
* written in Scala 2.10
* web framework play 2.1
* slick 1.0.0 as database access layer
* H2 in memory database for simple prototyping
* Amazon services java API 1.3.30
* Amazon services: S3, ElasticTranscoder, SNS, SQS
* Akka actors to carry out background processing of Amazon uploads, etc.

## What can you do with this code?
This code runs under play 2.1 in a stand alone mode. It will copy the files to the server, but will
fail to copy to Amazon S3 unless you set valid Amazon credentials in conf/AwsCredentials.properties.

As a prototype, there are hard coded resources defined in the code, for example the Amazon
bucket and queue names to name just a few. Also, the H2 database restarts each time you run the play
application.

