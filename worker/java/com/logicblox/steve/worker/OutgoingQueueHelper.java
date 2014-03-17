package com.logicblox.steve.worker;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.googlecode.protobuf.format.JsonFormat;
import com.logicblox.steve.protocol.Backend;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class OutgoingQueueHelper {
  AmazonSQS sqs;
  String _outgoing_url;
  String _job;

  public OutgoingQueueHelper(String url, String job)
  {
    setupSQS();
    this._outgoing_url = url;
    this._job = job;
  }

  private void setupSQS()
  {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));
  }

  public void notifyStatus(String status) {
    Backend.JobStatus.Builder msgBuilder = Backend.JobStatus.newBuilder();
    msgBuilder.setJob(_job);
    msgBuilder.setDatetime(System.currentTimeMillis() / 1000);

    msgBuilder.setMachine(getHostname());
    msgBuilder.setStatus(status);

    try
    {
      sendResult(msgBuilder.build());
    }
    catch (Exception e)
    {
      System.err.println("ERROR: Could not send status notification: "+e.getMessage());
    }
  }

  public void notifyFailure(Exception e) {
    Backend.JobFinished.Builder msgBuilder = getBuilder();
    msgBuilder.setSuccess(false);

    if (e instanceof InternalException)
    {
      msgBuilder.setErrorCode("INTERNAL_ERROR");
      msgBuilder.setErrorMessage(e.getMessage());
    }
    else
    {
      msgBuilder.setErrorCode("JOB_FAILED");
    }
    sendResult(msgBuilder.build());
  }

  public void notifySuccess() {
    Backend.JobFinished.Builder msgBuilder = getBuilder();
    msgBuilder.setSuccess(true);
    sendResult(msgBuilder.build());
  }

  private Backend.JobFinished.Builder getBuilder() {
    Backend.JobFinished.Builder msgBuilder = Backend.JobFinished.newBuilder();
    msgBuilder.setJob(_job);
    msgBuilder.setDatetime(System.currentTimeMillis() / 1000);

    msgBuilder.setMachine(getHostname());
    return msgBuilder;
  }

  public String getHostname() {
    String hostName;
    try
    {
      hostName = InetAddress.getLocalHost().getHostName();
    }
    catch (UnknownHostException ue)
    {
      hostName = "unknown";
    }
    return hostName;
  }

  private void sendResult(com.google.protobuf.Message msg)
  {
    String contents = new JsonFormat().printToString(msg);
    sqs.sendMessage(_outgoing_url,contents);
    System.out.println(contents);
  }

}
