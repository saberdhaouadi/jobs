package com.logicblox.steve.worker;

import com.amazonaws.AmazonClientException;
import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.Message;
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
import org.apache.commons.cli.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.IOUtils;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;


public class Main
{
  private S3Client client;
  AmazonSQS sqs;

  // Settings
  private static int _idle = 5;
  private static String _incoming_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  private static String _outgoing_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs-results";
  private static String _handle_file = "/var/lib/lb-steve-worker.handle";
  private static boolean _return_job = false;
  private static boolean _shutdown_on_idle = false;

  public static void parseArgs(String args[])
  {
    Options options = new Options();

    options.addOption(OptionBuilder.withLongOpt("idle")
            .withDescription("Number of minutes to stay idle before shutting down.")
            .withType(Number.class)
            .hasArg()
            .withArgName("minutes")
            .create());

    options.addOption(OptionBuilder.withLongOpt("incoming")
            .withDescription("Incoming queue URL")
            .hasArg()
            .withArgName("URL")
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
        _incoming_url = _cmdline.getOptionValue("incoming");
      if (_cmdline.hasOption("outgoing"))
        _outgoing_url = _cmdline.getOptionValue("outgoing");
      _return_job =  _cmdline.hasOption("return-job");
      _shutdown_on_idle =  _cmdline.hasOption("shutdown-on-idle");

    }
    catch( ParseException exp ) {
      System.err.println( "Error: " + exp.getMessage() );
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp( "lb-steve-worker", options );
      System.exit(1);
    }
  }

  public Main()
  {
    createS3Client();
    setupSQS();
  }

  public static void main(String[] args) throws Exception {
    parseArgs(args);
    Main m = new Main();

    if(_return_job) {
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
        System.err.println(String.format("Returning message with handle '%s' to %s.", handle, _incoming_url));
        sqs.changeMessageVisibility(_incoming_url, handle, 0);
      }
      catch(AmazonClientException e)
      {
        throw new InternalException(String.format("ERROR: Could return message to the %s.", _incoming_url), e);
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

      try
      {
        new JsonFormat().merge(IOUtils.toInputStream(job.getBody()), msgBuilder);
        msg = msgBuilder.build();
      }
      catch(IOException e)
      {
        System.err.println("ERROR: Invalid input message:\n"+job.getBody());
        continue;
      }

      SteveJob steve = new SteveJob(
              this.client,
              _outgoing_url,
              msg.getJob(),
              msg.getJobImpl(),
              msg.getInputList(),
              msg.getOutput(),
              msg.getTimeout()
      );

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
        removeIncoming(job);
      }
    }
  }

  private void removeIncoming(Message job) {
    try
    {
      sqs.deleteMessage(new DeleteMessageRequest(_incoming_url, job.getReceiptHandle()));
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

  private com.amazonaws.services.sqs.model.Message fetchJob() throws InterruptedException, IOException {
    long waitingSince = System.currentTimeMillis();

    while(true) {
      ReceiveMessageRequest receiveMessageRequest = new ReceiveMessageRequest(_incoming_url);
      receiveMessageRequest.setMaxNumberOfMessages(1);
      List<com.amazonaws.services.sqs.model.Message> messages = sqs.receiveMessage(receiveMessageRequest).getMessages();

      if (messages.size() == 1)
        return messages.get(0);

      // If idling for more than 5 minutes, poweroff machine
      if (_shutdown_on_idle && (System.currentTimeMillis() - waitingSince) / 1000 > _idle*60)
      {
        try
        {
          Process p = Runtime.getRuntime().exec("systemctl poweroff");
          p.waitFor();
        }
        finally
        {
          System.exit(0);
        }
      }
      Thread.sleep(5000);
    }
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
