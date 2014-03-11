package com.logicblox.steve.worker;

import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.logicblox.s3lib.*;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;


public class SteveJob {
  private String id;
  private String impl;
  private List<String> inputs;
  private URI output;
  private String drv;

  private String s3Bucket = "steve-jobs";
  private URI outputLog;

  private S3Client client;

  private File inputPath = new File("/tmp/job/in");
  private File outputPath = new File("/tmp/job/out");
  private File jobPath = new File("/tmp/job/job.tar.gz");

  public SteveJob(S3Client client, String id, String impl, List<String> inputs, String output) throws InternalException {
    this.id = id;
    this.impl = impl;
    this.inputs = inputs;
    this.client = client;

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
    System.out.println(String.format("%s: %s", id, msg));
  }

  public void run() throws Exception {
    try
    {
      setup();
      runJob();
    }
    finally
    {
      teardown();
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
      jobImplUri = Utils.getURI(uri);
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
      inputUri = Utils.getURI(input);
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid URI '"+input, e);
    }
    // Take basename, File/String conversion for removal of trailing slash (/)
    String basename = FilenameUtils.getBaseName(new File(inputUri.getPath()).toString());

    File f = new File(inputPath,basename);
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
    if (drv != null)
    {
      File logPath = new File(NixUtils.logPath(drv));

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

    // cleaning up directories
    log("Removing local in-/output...");
    cleanUp();
  }

  private void runJob() throws Exception {
    log("Running the actual job...");

    String nix = "<worker/nix/job.nix>";

    // determine .drv
    drv = NixUtils.nixInstantiate(nix);
    log(drv);

    // build .drv
    NixUtils.nixStoreRealise(drv, id);

  }

}
