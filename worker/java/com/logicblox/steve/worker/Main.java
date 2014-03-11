package com.logicblox.steve.worker;

import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.googlecode.protobuf.format.JsonFormat;
import com.logicblox.s3lib.DirectoryKeyProvider;
import com.logicblox.s3lib.KeyProvider;
import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.Utils;
import com.logicblox.steve.protocol.Backend;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.Executors;


public class Main
{
  private S3Client client;
  AmazonSQS sqs;
  String incoming_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  String outgoing_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs-results";

  public static void main(String[] args) throws InterruptedException, InternalException {
    Main m = new Main();
    m.createS3Client();
    m.setupSQS();

    while (true)
    {
      Backend.RunJob.Builder msgBuilder = Backend.RunJob.newBuilder();
      Backend.RunJob msg;
      com.amazonaws.services.sqs.model.Message job = m.fetchJob();

      try
      {
        new JsonFormat().merge(IOUtils.toInputStream(job.getBody()), msgBuilder);
        msg = msgBuilder.build();
      }
      catch(IOException e)
      {
        System.out.println("ERROR: Invalid input message:\n"+job.getBody());
        continue;
      }

      try
      {
        m.processMessage(msg);
        m.notifySuccess(msg);
      }
      catch (Exception e)
      {
        m.notifyFailure(msg, e);
        e.printStackTrace();
      }
      finally
      {
        String handle = job.getReceiptHandle();
        m.sqs.deleteMessage(new DeleteMessageRequest(m.incoming_url, handle));
      }
    }
  }

  private void setupSQS()
  {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));
  }

  private com.amazonaws.services.sqs.model.Message fetchJob() throws InterruptedException {

    while(true) {
      ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(incoming_url);
      receiveMessageRequest.setMaxNumberOfMessages(1);
      List<com.amazonaws.services.sqs.model.Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();

      if (messages.size() == 1)
        return messages.get(0);

      Thread.sleep(5000);
    }
  }

  private void notifyFailure(Backend.RunJob job, Exception e) {
    Backend.JobFinished.Builder msgBuilder = getBuilder(job);
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

  private void notifySuccess(Backend.RunJob job) {
    Backend.JobFinished.Builder msgBuilder = getBuilder(job);
    msgBuilder.setSuccess(true);
    sendResult(msgBuilder.build());
  }

  private Backend.JobFinished.Builder getBuilder(Backend.RunJob job) {
    Backend.JobFinished.Builder msgBuilder = Backend.JobFinished.newBuilder();
    msgBuilder.setJob(job.getJob());
    msgBuilder.setDatetime(System.currentTimeMillis() / 1000);

    String hostName;
    try
    {
      hostName = InetAddress.getLocalHost().getHostName();
    }
    catch (UnknownHostException ue)
    {
      hostName = "unknown";
    }
    msgBuilder.setMachine(hostName);
    return msgBuilder;
  }

  private void sendResult(com.google.protobuf.Message msg)
  {
    String contents = new JsonFormat().printToString(msg);
    sqs.sendMessage(outgoing_url,contents);
    System.out.println(contents);
  }

  private void processMessage(Backend.RunJob msg) throws Exception {
    System.out.println(String.format("%s: Starting...",msg.getJob()));
    SteveJob job = new SteveJob(
            this.client,
            msg.getJob(),
            msg.getJobImpl(),
            msg.getInputList(),
            msg.getOutput()
    );

    job.run();
    System.out.println(String.format("%s: Done!", msg.getJob()));
  }

  protected ListeningExecutorService getHttpExecutor()
  {
    int maxConcurrentConnections = 10;

    return MoreExecutors.listeningDecorator(Executors.newFixedThreadPool(maxConcurrentConnections));
  }

  protected ListeningScheduledExecutorService getInternalExecutor()
  {
    return MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(50));
  }

  protected S3Client createS3Client()
  {
    ListeningExecutorService uploadExecutor = getHttpExecutor();
    ListeningScheduledExecutorService internalExecutor = getInternalExecutor();

    long chunkSize = Utils.getDefaultChunkSize();
    int _retryCount = 10;

    this.client = new S3Client(
            null,
            uploadExecutor,
            internalExecutor,
            chunkSize,
            getKeyProvider());

    client.setRetryCount(_retryCount);

    return client;
  }

  protected KeyProvider getKeyProvider()
  {
    File dir = new File(Utils.getDefaultKeyDirectory());
    return new DirectoryKeyProvider(dir);
  }

}
