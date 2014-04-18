package com.logicblox.steve.worker;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.sqs.model.Message;
import com.logicblox.s3lib.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;


public class SteveJob {
  public final OutgoingQueueHelper outgoing;
  private String id;
  private String impl;
  private List<String> inputs;
  private URI output;
  private String drv;
  private long timeout;

  private String s3Bucket = "steve-jobs";
  private URI outputLog;

  private S3Client client;

  private File inputPath = new File("/tmp/job/in");
  private File outputPath = new File("/tmp/job/out");
  private File jobPath = new File("/tmp/job/job.tar.gz");

  public SteveJob(S3Client client, String outgoing_url, String id, String impl, List<String> inputs, String output, long timeout) throws InternalException {
    this.id = id;
    this.impl = impl;
    this.inputs = inputs;
    this.client = client;
    this.outgoing = new OutgoingQueueHelper(outgoing_url, id);
    this.timeout = timeout;

    try
    {
      this.output = new URI(output);
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid output : "+output, e);
    }
    try
    {
      outputLog = new URI(String.format("s3://%s/jobs/%s/log", s3Bucket, id));
    }
    catch(URISyntaxException e)
    {
      throw new InternalException("Invalid output log URI", e);
    }
  }

  public void log(String msg)
  {
    System.err.println(String.format("%s: %s", id, msg));
  }

  public void run() throws Exception {
    log("Starting...");
    try
    {
      outgoing.notifyStart();
      setup();
      runJob();
      outgoing.notifySuccess();
      log("Done!");
    }
    catch (Exception e)
    {
      outgoing.notifyFailure(e);
      e.printStackTrace();
    }
    finally
    {
      try
      {
        teardown();
      }
      catch(InternalException e)
      {
        outgoing.notifyFailure(e);
      }
    }
  }

  private void deleteDirectory(File path) throws InternalException {
    if (path.exists()) {
      try {
        FileUtils.deleteDirectory(path);
      }
      catch(IOException e)
      {
        throw new InternalException("Could not remove directory "+path, e);
      }
    }
  }

  private void cleanUp() throws InternalException {
    deleteDirectory(new File("/tmp/job"));
  }

  private void setup() throws InternalException {
    cleanUp();

    inputPath.mkdirs();
    outputPath.mkdirs();

    try
    {
      ProcessBuilder pb = new ProcessBuilder("chmod", "-R", "777", outputPath.toString());
      pb.start().waitFor();
    }
    catch(Exception e)
    {
    }

    downloadJobImpl();
    // download inputs
    for(String input: inputs)
    {
      downloadInput(input);
    }

  }

  private void downloadJobImpl() throws InternalException {
    String uri = String.format("s3://%s/jobs-impl/%s.tar.gz", s3Bucket, impl);
    log(uri);
    URI jobImplUri;
    try
    {
      jobImplUri = com.logicblox.s3lib.Utils.getURI(uri);
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid URI '"+uri, e);
    }

    try
    {
        client.download(new File("/tmp/job/job.tar.gz"), jobImplUri).get();
    }
    catch(Exception e)
    {
      throw new InternalException("Could not download job implementation "+impl, e);
    }
  }

  private void downloadInput(String input) throws InternalException {
    log(input);
    URI inputUri;
    try
    {
      inputUri = com.logicblox.s3lib.Utils.getURI(input);
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid URI '"+input, e);
    }
    // Strip trailing slash
    String last = new File(inputUri.getPath()).toString();
    // Use the last part of the URL
    last = last.substring(last.lastIndexOf('/') + 1);

    File f = new File(inputPath,last);
    try
    {
      if (input.endsWith("/"))
        client.downloadDirectory(f, inputUri, true, true).get();
      else
        client.download(f, inputUri).get();
    }
    catch(Exception e)
    {
      throw new InternalException("Could not download input '"+input, e);
    }
  }

  private void teardown() throws InternalException {
    log("Tearing down...");

    ObjectMetadata log = null;
    try
    {
      log = client.exists(s3Bucket, String.format("jobs/%s/log", id)).get();
    }
    catch(Exception e)
    {
      throw new InternalException("Could not determine if log file already exists in S3.", e);
    }

    if (log == null)
    {
      // client.exists(,).get();
      if (drv != null)
      {
        File logPath = new File(Utils.nixLogPath(drv));

        if(! logPath.exists()) {
          log("No log file found, going on.");
        }
        else
        {
          // upload logs
          try
          {
            log("Uploading log...[%s/%s]".format(logPath.toString(), outputLog));
            client.upload(logPath, outputLog).get();
          }
          catch (Exception e)
          {
            throw new InternalException("Error uploading log to "+outputLog,e);
          }
        }
      }

      // upload output
      try
      {
        log("Uploading output...");
        client.uploadDirectory(outputPath, output, null).get();
      }
      catch (Exception e)
      {
        throw new InternalException("Error uploading output files to "+output,e);
      }
    }
    else
    {
      log("ERROR: Found log file, probably means the job was executed elsewhere. Skipping upload of logs and results.");
    }
    // cleaning up directories
    log("Removing local in-/output...");
    cleanUp();
  }

  private void runJob() throws Exception {
    log("Running the actual job...");

    String nix = "<worker/nix/job.nix>";

    // determine .drv
    drv = nixInstantiate(nix);

    // build .drv
    nixStoreRealise(drv, id);
  }


  public String nixInstantiate(String file) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("nix-instantiate", file);

    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0)
    {
      throw new Exception("nix-instantiate failed with exit code "+exit+"\n\n"+Utils.streamToString(p.getErrorStream()));
    }

    return Utils.streamToString(p.getInputStream());
  }

  public void nixStoreRealise(String file, String job) throws Exception {
    // build up the command line to using a 'java.io.File'
    CommandLine commandLine = new CommandLine("nix-store");
    commandLine.addArgument("-r");
    commandLine.addArgument(file);
    commandLine.addArgument("--timeout");
    commandLine.addArgument(Long.toString(timeout));

    Executor executor = new DefaultExecutor();
    executor.setExitValues(null);

    // handle output
    SteveJobLogHandler outputStream = new SteveJobLogHandler(this);
    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
    executor.setStreamHandler(streamHandler);

    int exit;
    try {
      exit = executor.execute(commandLine);
    } catch (Exception ex) {
      throw new InternalException("Execute exception: "+ ex.getMessage(), ex);
    }

    if (exit != 0)
    {
      File logPath = new File(Utils.nixLogPath(file));
      if(logPath.exists())
      {
        throw new JobFailedException("nix-store failed with exit code "+exit);
      }
      else
      {
        throw new InternalException("One of the dependencies of the job likely failed, as no log was found.");
      }
    }
  }


}
