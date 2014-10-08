package com.logicblox.steve.worker;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.sqs.model.Message;

import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;
import com.logicblox.steve.common.Data;
import com.logicblox.concurrent.MoreFutures;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ExecutionException;

import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.Executor;
import org.apache.commons.exec.PumpStreamHandler;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class SteveJob
{
  public final OutgoingQueueHelper _outgoing;
  private String _id;
  private String _impl;
  private List<Data> _inputs;
  private URI _output;
  private String _drv;
  private long _timeout;

  private String _s3Bucket;
  private URI _outputLog;

  private S3Client _client;

  private File _inputPath = new File("/tmp/job/in");
  private File _outputPath = new File("/tmp/job/out");
  private File _jobPath = new File("/tmp/job/job.tar.gz");

  private boolean _timedOut = false;
  private boolean _killed = false;

  public SteveJob(S3Client client, String s3Bucket, String outgoingUrl, String id, String impl, List<Data> inputs, String output, long timeout)
  throws InternalException
  {
    _id = id;
    _impl = impl;
    _inputs = inputs;
    _client = client;
    _outgoing = new OutgoingQueueHelper(outgoingUrl, _id);
    _timeout = timeout;
    _s3Bucket = s3Bucket;

    try
    {
      _output = new URI(output);
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid output : "+ output, e);
    }
    try
    {
      _outputLog = new URI(String.format("s3://%s/jobs/%s/log", _s3Bucket, _id));
    }
    catch(URISyntaxException e)
    {
      throw new InternalException("Invalid output log URI", e);
    }
  }

  public void log(String msg)
  {
    System.err.println(String.format("%s: %s", _id, msg));
  }

  public void run() throws Exception
  {
    log("Starting..." + _id);
    try
    {
      _outgoing.notifyStart();
      setup();
      runJob();
      log("Successfully executed " + _id);
      List<S3File> output = uploadOutput();
      _outgoing.notifySuccess(output);
      log("Successfully uploaded output files for job " + _id);
    }
    catch (JobKilledException k)
    {
      _outgoing.notifyStatus("Job was killed. It will be restarted on another worker.");
      _killed = true;
    }
    catch (Exception e)
    {
      log("Failure executing " + _id);
      _outgoing.notifyFailure(e);
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
        _outgoing.notifyFailure(e);
      }
    }
  }

  private void deleteDirectory(File path) throws InternalException
  {
    if (path.exists())
    {
      try
      {
        FileUtils.deleteDirectory(path);
      }
      catch(IOException e)
      {
        throw new InternalException("Could not remove directory "+path, e);
      }
    }
  }

  private void cleanUp() throws InternalException
  {
    deleteDirectory(new File("/tmp/job"));
  }

  private void setup() throws Exception
  {
    cleanUp();

    _inputPath.mkdirs();
    _outputPath.mkdirs();

    try
    {
      ProcessBuilder pb = new ProcessBuilder("chmod", "-R", "777", _outputPath.toString());
      pb.start().waitFor();
    }
    catch(Exception e)
    {
    }

    downloadJobImpl();

    // download inputs
    List<ListenableFuture<List<S3File>>> inputFiles = new ArrayList<ListenableFuture<List<S3File>>>();
    for(Data input: _inputs)
    {
      inputFiles.add(downloadInput(input));
    }
    try
    {
      MoreFutures.concat(Futures.allAsList(inputFiles)).get();
    }
    catch(Exception e)
    {
      throw new DownloadInputFailedException(e.getMessage(), e);
    }
  }

  private void downloadJobImpl() throws InternalException
  {
    String uri;
    if(_impl.startsWith("s3://"))
      uri = _impl;
    else
      uri = String.format("s3://%s/jobs-impl/%s.tar.gz", _s3Bucket, _impl);

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
      _client.download(new File("/tmp/job/job.tar.gz"), jobImplUri).get();
    }
    catch(Exception e)
    {
      e.printStackTrace();
      throw new InternalException("Could not download job implementation '" + _impl + "' from '" + uri + "'", e);
    }
  }

  private ListenableFuture<List<S3File>> downloadInput(Data input) throws InternalException
  {
    log("Downloading input '" + input.toString() + "'");
    URI inputUri;
    try
    {
      inputUri = com.logicblox.s3lib.Utils.getURI(input.getLocation());
    }
    catch (URISyntaxException e)
    {
      throw new InternalException("Invalid URI '"+input, e);
    }

    // Strip trailing slash
    String last = new File(inputUri.getPath()).toString();
    // Use the last part of the URL
    last = last.substring(last.lastIndexOf('/') + 1);

    File f = new File(_inputPath,last);
    try {
      if (input.getLocation().endsWith("/"))
        return _client.downloadDirectory(f, inputUri, true, true);
      else {
        List<ListenableFuture<S3File>> l = new ArrayList();
        l.add(_client.download(f, inputUri));
        return Futures.allAsList(l);
      }
    }
    catch(Exception e) {
        return Futures.immediateFailedFuture(new InternalException("Error downloading input "+input.getLocation()));
    }
  }

  private List<S3File> uploadOutput() throws InternalException
  {
    try
    {
      log("Uploading output...");
      return _client.uploadDirectory(_outputPath, _output, null).get();
    }
    catch (Exception e)
    {
      throw new InternalException("Error uploading output files to " + _output, e);
    }
  }

  private void teardown() throws InternalException
  {
    log("Tearing down...");

    ObjectMetadata log = null;
    try
    {
      // TODO make sure that jobs can be retried/re-executed
      log = _client.exists(_s3Bucket, String.format("jobs/%s/log", _id)).get();
    }
    catch(Exception e)
    {
      throw new InternalException("Could not determine if log file already exists in S3.", e);
    }

    // TODO rework to make sure we don't overwrite uploaded results
    // from different jobs (moved this out to avoid reporting success
    // before upload)
    if (log == null)
    {
      if (_drv != null)
      {
        File logPath = new File(Utils.nixLogPath(_drv));

        if(_killed)
        {
          log("Job was killed, not uploading log file.");
        }
        else if(!logPath.exists())
        {
          log("No log file found, going on.");
        }
        else
        {
          // upload logs
          try
          {
            log("Uploading log...[%s/%s]".format(logPath.toString(), _outputLog));
            _client.upload(logPath, _outputLog).get();
          }
          catch (Exception e)
          {
            throw new InternalException("Error uploading log to " + _outputLog, e);
          }
        }
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

  private void runJob() throws Exception
  {
    log("Running the actual job...");

    String nix = "<worker/nix/job.nix>";

    // determine .drv
    _drv = nixInstantiate(nix);

    // build .drv
    nixStoreRealise(_drv, _id);
  }


  public String nixInstantiate(String file) throws Exception
  {
    ProcessBuilder pb = new ProcessBuilder("nix-instantiate", file);

    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0)
    {
      throw new Exception("nix-instantiate failed with exit code " + exit + "\n\n" + Utils.streamToString(p.getErrorStream()));
    }

    return Utils.streamToString(p.getInputStream());
  }

  public void nixStoreRealise(String file, String job) throws Exception
  {
    // build up the command line to using a 'java.io.File'
    CommandLine commandLine = new CommandLine("nix-store");
    commandLine.addArgument("-r");
    commandLine.addArgument(file);
    commandLine.addArgument("--timeout");
    commandLine.addArgument(Long.toString(_timeout));

    Executor executor = new DefaultExecutor();
    executor.setExitValues(null);

    // handle output
    SteveJobLogHandler outputStream = new SteveJobLogHandler(this);
    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
    executor.setStreamHandler(streamHandler);

    int exit;
    try
    {
      exit = executor.execute(commandLine);
    }
    catch (Exception ex)
    {
      throw new InternalException("Execute exception: "+ ex.getMessage(), ex);
    }

    if (exit != 0)
    {
      File logPath = new File(Utils.nixLogPath(file));
      if(_timedOut)
      {
        throw new JobTimedOutException();
      }
      else if(exit == 1)
      {
        throw new JobKilledException();
      }
      else if(logPath.exists())
      {
        throw new JobFailedException("nix-store failed with exit code "+exit);
      }
      else
      {
        throw new InternalException("One of the dependencies of the job likely failed, as no log was found.");
      }
    }
  }

  public long getTimeout()
  {
    return _timeout;
  }

  public void setTimedOut()
  {
    _timedOut = true;
  }

  public boolean wasKilled()
  {
    return _killed;
  }
}
