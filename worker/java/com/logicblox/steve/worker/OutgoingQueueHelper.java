package com.logicblox.steve.worker;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.googlecode.protobuf.format.JsonFormat;

import com.logicblox.s3lib.S3File;
import com.logicblox.steve.protocol.Backend;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

public class OutgoingQueueHelper {
  AmazonSQS sqs;
  String _outgoing_url;
  String _job;

  public OutgoingQueueHelper(String url, String job) {
    setupSQS();
    _outgoing_url = url;
    _job = job;
  }

  private void setupSQS() {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));
  }

  public void notifyStatus(String status) {
    Backend.JobStatus.Builder msgBuilder = getBuilder();
    msgBuilder.setStatusCode(Backend.StatusCode.PROGRESS);
    msgBuilder.setProgressDetails(
            Backend.ProgressDetails.newBuilder()
                    .setMessage(status));

    try {
      sendResult(msgBuilder.build());
    } catch (Exception e) {
      System.err.println("ERROR: Could not send status notification: " + e.getMessage());
    }
  }

  public void notifyFailure(Exception e) {
    Backend.JobStatus.Builder msgBuilder = getBuilder();
    msgBuilder.setStatusCode(Backend.StatusCode.FAILED);

    if (e instanceof JobFailedException) {
      msgBuilder.setFailedDetails(
              Backend.FailedDetails.newBuilder()
                      .setErrorCode("JOB_FAILED")
                      .setErrorMessage(e.getMessage()));
    } else if (e instanceof JobTimedOutException) {
      msgBuilder.setFailedDetails(
              Backend.FailedDetails.newBuilder()
                      .setErrorCode("JOB_TIMED_OUT"));
    } else if (e instanceof DownloadInputFailedException) {
      msgBuilder.setFailedDetails(
              Backend.FailedDetails.newBuilder()
                      .setErrorCode("DOWNLOAD_FAILED")
                      .setErrorMessage(e.getMessage()));
    } else if (e instanceof UploadOutputFailedException) {
      msgBuilder.setFailedDetails(
              Backend.FailedDetails.newBuilder()
                      .setErrorCode("UPLOAD_FAILED")
                      .setErrorMessage(e.getMessage()));
    } else {
      msgBuilder.setFailedDetails(
              Backend.FailedDetails.newBuilder()
                      .setErrorCode("INTERNAL_ERROR"));
    }

    sendResult(msgBuilder.build());
  }

  public void notifySuccess(List<S3File> result, long cpuUsage, long maxMemory, long maxDiskUsage) {
    Backend.JobStatus.Builder msgBuilder = getBuilder();
    msgBuilder.setStatusCode(Backend.StatusCode.SUCCEEDED);

    Backend.SucceededDetails.Builder details = Backend.SucceededDetails.newBuilder();
    for (S3File f : result) {
      details.addOutput(
              Backend.File.newBuilder()
                      .setUrl("s3://" + f.getBucketName() + "/" + f.getKey())
                      .setHash("etag:" + f.getETag()));
    }

    msgBuilder.setSucceededDetails(details);
    msgBuilder.setResourceUsage(Backend.ResourceUsage.newBuilder().setCpuUsage(cpuUsage).setMaxMemory(maxMemory).setMaxDiskUsage(maxDiskUsage));
    sendResult(msgBuilder.build());
  }

  public void notifyStart() {
    Backend.JobStatus.Builder msgBuilder = getBuilder();
    msgBuilder.setStatusCode(Backend.StatusCode.STARTED);
    sendResult(msgBuilder.build());
  }

  private Backend.JobStatus.Builder getBuilder() {
    return
            Backend.JobStatus.newBuilder()
                    .setJob(_job)
                    .setTimestamp(System.currentTimeMillis())
                    .setMachine(getHostname());
  }

  public String getHostname() {
    String hostName;
    try {
      hostName = InetAddress.getLocalHost().getHostName();
    } catch (UnknownHostException ue) {
      hostName = "unknown";
    }
    return hostName;
  }

  private void sendResult(com.google.protobuf.Message msg) {
    String contents = new JsonFormat().printToString(msg);
    sqs.sendMessage(_outgoing_url, contents);
    System.err.println(contents);
  }

}
