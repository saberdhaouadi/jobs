package com.logicblox.steve.worker;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.googlecode.protobuf.format.JsonFormat;
import com.amazonaws.util.EC2MetadataUtils;

import com.logicblox.s3lib.S3Client;
import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.S3Utils;

import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;

import org.joda.time.format.ISODateTimeFormat;
import com.google.gson.Gson;
import org.joda.time.DateTime;
import java.util.HashMap;
import java.util.Map;

public class Main
{
  class EC2DynamicMetadata {
    String pendingTime;
  }

  private class ResetMessageVisibilityTimeout implements Runnable
  {
    private String _handle;
    public ResetMessageVisibilityTimeout(String handle)
    {
      _handle = handle;
    }

    @Override
    public void run() {
      while(!Thread.currentThread().isInterrupted())
      {
        try
        {
          sqs.changeMessageVisibility(_incomingUrl, _handle, 300);
        }
        catch(Exception e)
        {
          // We don't care much about exceptions updating the message
          // visibility timeout, we'll just log it.
          System.err.println("WARNING: Failed to update visibility timeout for message: " + e.getMessage());
        }

        try
        {
          Thread.sleep(60000);
        }
        catch(InterruptedException e)
        {
          Thread.currentThread().interrupt();
        }
      }
    }
  }

  private S3Client client;
  AmazonSQS sqs;

  // Settings
  private static int _idle = 2;
  private static String _incomingUrl = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  private static String _outgoingUrl = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs-results";
  private static String _handle_dir = "/var/lib/lb-steve";
  private static String _handle_file = _handle_dir + "/lb-steve-worker.handle";
  private static String _s3Bucket = "steve-jobs";
  private static boolean _returnJob = false;
  private static boolean _shutdownOnIdle = false;

  public static void parseArgs(String args[])
  {
    Options options = new Options();

    options.addOption(OptionBuilder.withLongOpt("idle")
            .withDescription("Minimum number of minutes idling before shutting down.")
            .withType(Number.class)
            .hasArg()
            .withArgName("minutes")
            .create());

    options.addOption(OptionBuilder.withLongOpt("incoming")
            .withDescription("Incoming queue URL")
            .hasArg()
            .withArgName("URL")
            .create());

    options.addOption(OptionBuilder.withLongOpt("bucket")
            .withDescription("S3 bucket name")
            .hasArg()
            .withArgName("NAME")
            .create());

    options.addOption(OptionBuilder.withLongOpt("outgoing")
            .withDescription("Outgoing queue URL")
            .hasArg()
            .withArgName("URL")
            .create());


    options.addOption(
            OptionBuilder.withLongOpt("return-job")
            .withDescription("Return current message to the incoming SQS queue.")
            .create());

    options.addOption(
            OptionBuilder.withLongOpt("shutdown-on-idle")
                    .withDescription("Shutdown machine on idle.")
                    .create());

    CommandLineParser parser = new BasicParser();
    try {
      CommandLine _cmdline = parser.parse( options, args );
      if (_cmdline.hasOption("idle"))
        _idle = ((Number)_cmdline.getParsedOptionValue("idle")).intValue();
      if (_cmdline.hasOption("incoming"))
        _incomingUrl = _cmdline.getOptionValue("incoming");
      if (_cmdline.hasOption("outgoing"))
        _outgoingUrl = _cmdline.getOptionValue("outgoing");
      if (_cmdline.hasOption("bucket"))
        _s3Bucket = _cmdline.getOptionValue("bucket");
      _returnJob =  _cmdline.hasOption("return-job");
      _shutdownOnIdle =  _cmdline.hasOption("shutdown-on-idle");

    }
    catch( ParseException exp ) {
      System.err.println( "Error: " + exp.getMessage() );
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp( "lb-steve-worker", options );
      System.exit(1);
    }

    File handleDir = new File(_handle_dir);
    handleDir.mkdirs();
  }

  public Main()
  {
    // TODO pass in a configuration for S3
    this.client = S3Utils.createS3Client(null);

    setupSQS();
  }

  public static void main(String[] args) throws Exception {
    parseArgs(args);
    Main m = new Main();

    if(_returnJob) {
      m.returnJob();
    }
    else
    {
      m.processMessages();
    }
  }

  private void returnJob() throws Exception {
    File h = new File(_handle_file);
    if(h.exists())
    {
      String handle = "";
      try
      {
        handle = FileUtils.readFileToString(h);
      }
      catch(IOException e)
      {
        throw new InternalException(String.format("ERROR: Could not read message at %s.", _handle_file), e);
      }

      try
      {
        System.err.println(String.format("Returning message with handle '%s' to %s.", handle, _incomingUrl));
        sqs.changeMessageVisibility(_incomingUrl, handle, 0);
      }
      catch(AmazonClientException e)
      {
        throw new InternalException(String.format("ERROR: Could return message to the %s.", _incomingUrl), e);
      }
    }
    else
    {
      System.err.println(String.format("WARNING: No message found at %s", _handle_file));
    }
  }

  private void processMessages() throws InterruptedException, IOException, InternalException {
    while (true)
    {
      Backend.RunJob.Builder msgBuilder = Backend.RunJob.newBuilder();
      Backend.RunJob msg;
      com.amazonaws.services.sqs.model.Message job = fetchJob();

      try
      {
        FileUtils.writeStringToFile(new File(_handle_file), job.getReceiptHandle());
      }
      catch (IOException e)
      {
        System.err.println(String.format("WARNING: Could not write file with current message handler to %s",_handle_file));
      }

      System.err.println("received job request: " + job.getBody());

      try
      {
        new JsonFormat().merge(IOUtils.toInputStream(job.getBody()), msgBuilder);
        msg = msgBuilder.build();
      }
      catch(Exception e)
      {
        System.err.println("ERROR: Invalid input message:\n"+job.getBody());
        removeIncoming(job);
        continue;
      }


      Map<String, String> metadata = new HashMap<String, String>();
      for(Backend.Param p: msg.getMetadataList()) {
        metadata.put(p.getKey(), p.getValue());
      }

      SteveJob steve = new SteveJob(
              this.client,
              _s3Bucket,
              _outgoingUrl,
              msg.getJob(),
              msg.getJobImpl(),
              Conversions.convertFileToData(msg.getInputList()),
              msg.getOutput(),
              msg.getTimeout(),
              metadata
      );

      Thread resetTimeout = new Thread(new ResetMessageVisibilityTimeout(job.getReceiptHandle()));
      resetTimeout.start();
      try
      {
        steve.run();
      }
      catch(Exception e)
      {
        System.err.println("ERROR: Unhandled exception: "+e.getMessage());
        e.printStackTrace();
      }
      finally
      {
        resetTimeout.interrupt();
        if(!steve.wasKilled()) removeIncoming(job);
      }
    }
  }

  private void removeIncoming(Message job) {
    try
    {
      sqs.deleteMessage(new DeleteMessageRequest(_incomingUrl, job.getReceiptHandle()));
      if( ! FileUtils.deleteQuietly(new File(_handle_file)) )
      {
        System.err.println("WARNING: Couldn't delete file with current message handler.");
      }
    }
    catch(Exception e)
    {
      System.err.println("ERROR: Deleting message from incoming queue failed! "+e.getMessage());
    }
  }

  private void setupSQS()
  {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));
  }

  private EC2DynamicMetadata getMetadata()
  {
    String js = EC2MetadataUtils.getData("/latest/dynamic/instance-identity/document");
    EC2DynamicMetadata md = new Gson().fromJson(js, EC2DynamicMetadata.class);
    return md;
  }

  private com.amazonaws.services.sqs.model.Message fetchJob() throws InterruptedException, IOException {
    long waitingSince = System.currentTimeMillis();

    while(true) {
      try {
        ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(_incomingUrl);
        receiveMessageRequest.setMaxNumberOfMessages(1);
        List<com.amazonaws.services.sqs.model.Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();

        if (messages.size() == 1)
          return messages.get(0);
      }
      catch(Exception e) {
        System.err.println("ERROR: Problem receiving SQS message: "+e.getMessage());
      }
      finally{
        // If idling for more than x minutes, poweroff machine
        boolean idleTooLong = (System.currentTimeMillis() - waitingSince) / 1000 > _idle * 60;

        long nextInstanceHour;
        try {
          DateTime dt = ISODateTimeFormat.dateTimeParser().parseDateTime(getMetadata().pendingTime);

          long diffInMillis = DateTime.now().getMillis() - dt.getMillis();
          nextInstanceHour = 60 - ((diffInMillis % 3600000) / 60000);
        } catch (Exception e) {
          // If anything goes wrong in determining the number of minutes till next instance
          // hour, default to 0, which will cause the instance to shutdown when idling for x
          // minutes
          nextInstanceHour = 0;
          System.err.println("WARNING: Could not determine start of next instance hour: " + e.getMessage());
        }

        if (_shutdownOnIdle && idleTooLong && nextInstanceHour <= 3) {
          try {
            Process p = Runtime.getRuntime().exec("shutdown-self");
            p.waitFor();
          } finally {
            System.exit(0);
          }
        }

        Thread.sleep(2000);
      }
    }
  }
}
