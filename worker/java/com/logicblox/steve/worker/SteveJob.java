package com.logicblox.steve.worker;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.sqs.model.Message;

import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;
import com.logicblox.steve.common.Data;
import com.logicblox.concurrent.MoreFutures;

import java.io.File;
import java.io.IOException;
import java.lang.Exception;
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
import com.google.gson.Gson;

import java.util.Map;

public class SteveJob {
  public final OutgoingQueueHelper _outgoing;
  private String _id;
  private String _impl;
  private List<Data> _inputs;
  private URI _output;
  private String _drv;
  private long _timeout;
  private Map<String, String> _metadata;
  private int _receiveCount;
  private String _outputEncryptionKey;
  private String _account;

  private String _s3Bucket;
  private URI _outputLog;
  private URI _outputLbLogs;

  private S3Client _client;

  private File _jobPath = new File("/tmp/job");
  private File _inputPath = new File("/tmp/job/in");
  private File _outputPath = new File("/tmp/job/out");
  private File _logOutputPath = new File("/tmp/job/log");
  private File _lbDeploymentHomePath = new File("/tmp/job/lb_deployment");
  private File _lbDeploymentLogsPath = new File(_lbDeploymentHomePath,"logs");
  private File _lbLogsPath = new File("/tmp/job/log/lb-logs.tgz");
  private File _jobPath = new File("/tmp/job/job.tar.gz");
  private File _metadataPath = new File("/tmp/job/in/metadata.json");

  private boolean _timedOut = false;
  private boolean _killed = false;
  private boolean _diskFull = false;
  private boolean _completed = false;
  private boolean _cancelled = false;
  private String _internalError = null;

  private int _jobExitCode = 0;

  private long _diskFreeStart = 0;
  private long _maxDiskUsage = 0;
  private long _cpuUsage = 0;
  private long _maxMemory = 0;

  private File _keyDir = new File(com.logicblox.s3lib.Utils.getDefaultKeyDirectory());
  private File _shellDir = new File("/tmp/shell");
  private SteveKeyServerHelper _keyHelper;
  private File _cpuacct = new File("/sys/fs/cgroup/cpu,cpuacct/system.slice/nix-daemon.service/cpuacct.usage");
  private File _memacct = new File("/sys/fs/cgroup/memory/system.slice/nix-daemon.service/memory.memsw.max_usage_in_bytes");

  public SteveJob(S3Client client, String s3Bucket, String outgoingUrl, String id, String impl, List<Data> inputs, String output, String outputEncryptionKey, long timeout, Map<String, String> metadata, String account, int receiveCount, SteveKeyServerHelper keyHelper)
          throws InternalException {
    _id = id;
    _impl = impl;
    _inputs = inputs;
    _client = client;
    _outgoing = new OutgoingQueueHelper(outgoingUrl, _id);
    _timeout = timeout;
    _s3Bucket = s3Bucket;
    _metadata = metadata;
    _receiveCount = receiveCount;
    _outputEncryptionKey = outputEncryptionKey;
    _keyHelper = keyHelper;
    _account = account;

    try {
      _output = new URI(output);
    } catch (URISyntaxException e) {
      throw new InternalException("Invalid output : " + output, e);
    }
    try {
      _outputLog = new URI(String.format("s3://%s/jobs/%s/log", _s3Bucket, _id));
      _outputLbLogs = new URI(String.format("s3://%s/jobs/%s/lb-logs.tgz", _s3Bucket, _id));
    } catch (URISyntaxException e) {
      throw new InternalException("Invalid output log URI", e);
    }
  }

  public void log(String msg) {
    System.err.println(String.format("%s: %s", _id, msg));
  }

  private void readCounters() {
    try {
      _cpuUsage = Long.parseLong(FileUtils.readFileToString(_cpuacct).trim());
      _maxMemory = Long.parseLong(FileUtils.readFileToString(_memacct).trim());
    }
    catch(NumberFormatException e) {
      log("Could not parse the resource usage from cgroups: "+ e.getMessage());
      e.printStackTrace();
    }
    catch(IOException e) {
      log("Could not read resource usage from cgroups: " + e.getMessage());
      e.printStackTrace();
    }
  }

  public void run() throws Exception {
    log("Starting..." + _id);

    try {
      if(previousLogExists()) {
        log("Skipping job, because previous log was found.");
        _completed = true;
        return;
      }

      if(jobCancelled()) {
        log("Skipping job, because it was cancelled.");
        _cancelled = true;
        return;
      }

      _outgoing.notifyStart();
      setup();
      runJob();
      log("Successfully executed " + _id);
      readCounters();

      // Do not upload files when previous log already exists.
      if(!previousLogExists()) {
        List<S3File> output = uploadOutput();
        _outgoing.notifySuccess(output, _cpuUsage, _maxMemory, _maxDiskUsage);
        log("Successfully uploaded output files for job " + _id);
      }
      teardown();
    } catch (JobKilledException k) {
      _outgoing.notifyStatus("Job was killed. It will be restarted on another worker.");
      _killed = true;
    } catch (InternalException e) {
      if(_receiveCount >= 5) {
        _outgoing.notifyFailure(new InternalException("Retried job multiple time, but keep hitting internal error."), _cpuUsage, _maxMemory, _maxDiskUsage);
      } else {
        _outgoing.notifyStatus("There was an internal error while executing the job. It will be restarted on another worker.");
        if(e.getCause() != null) {
          e.printStackTrace();
        }
        throw e;
      }
    } catch (Exception e) {
      log("Failure executing " + _id + ": " + e.getMessage());
      e.printStackTrace();
      readCounters();
      _outgoing.notifyFailure(e, _cpuUsage, _maxMemory, _maxDiskUsage);
      teardown();
    }
  }

  private void deleteDirectory(File path) throws InternalException {
    if (path.exists()) {
      try {
        FileUtils.deleteDirectory(path);
      } catch (IOException e) {
        throw new InternalException("Could not remove directory " + path, e);
      }
    }
  }

  private void cleanUp() throws InternalException {
    deleteDirectory(new File("/tmp/job"));
    deleteDirectory(_keyDir);
  }

  private void setupEncryptionKeys() throws InternalException {
    Map<String, String> keys;
    try {
      System.out.println("Fetching keys...");
      keys = _keyHelper.getKeys(_account);
      System.out.println("Received "+keys.keySet().size()+" keys.");
      _keyDir.mkdirs();
      System.out.println("Created "+_keyDir);

      for(String key : keys.keySet()) {
        System.out.println("Writing "+_keyDir+"/"+key+".pem");
        FileUtils.writeStringToFile(new File(_keyDir,key+".pem"), keys.get(key));
      }
    }
    catch(Exception e) {
      throw new InternalException("Could not fetch encryption keys.", e);
    }
  }

  private void setup() throws Exception {
    cleanUp();

    _jobPath.mkdirs();
    _inputPath.mkdirs();
    _outputPath.mkdirs();
    _logOutputPath.mkdirs();
    _lbDeploymentHomePath.mkdirs();

    _diskFreeStart = _inputPath.getFreeSpace();

    downloadJobImpl();

    if(_outputEncryptionKey != null) {
      setupEncryptionKeys();
    }

    for (Data input : _inputs) {
      try {
        List<S3File> fs = downloadInput(input).get();
        int failed = 0;
        for(S3File f: fs) {
          if(f==null) {
            failed++;
          }
        }
        if(failed > 0) {
          throw new DownloadInputFailedException(failed+" out of "+fs.size()+" downloads of input `"+input.getLocation()+"` failed.");
        }
      }
      catch (ExecutionException e) {
        throw new DownloadInputFailedException(e.getMessage(), e);
      }
    }

    try {
      String data = new Gson().toJson(_metadata);
      FileUtils.writeStringToFile(_metadataPath, data);
    } catch (IOException e) {
      throw new InternalException("Could not write metadata.", e);
    }

    try {
      ProcessBuilder pb = new ProcessBuilder("chmod", "-R", "777", _jobPath.toString());
      Process p = pb.start();
      p.waitFor();
      p.destroy();
    } catch (Exception e) {
    }

    resetCounters();
  }

  private void resetCounters() {
    try {
      FileUtils.writeStringToFile(_memacct, "-1");
      FileUtils.writeStringToFile(_cpuacct, "0");
    }
    catch (IOException e) {
      log("Could not reset cgroup counters: "+ e.getMessage());
      e.printStackTrace();
    }
  }

  private void downloadJobImpl() throws InternalException {
    String uri;
    if (_impl.startsWith("s3://"))
      uri = _impl;
    else
      uri = String.format("s3://%s/jobs-impl/%s.tar.gz", _s3Bucket, _impl);

    log(uri);
    URI jobImplUri;
    try {
      jobImplUri = com.logicblox.s3lib.Utils.getURI(uri);
    } catch (URISyntaxException e) {
      throw new InternalException("Invalid URI '" + uri, e);
    }

    try {
      _client.download(new File("/tmp/job/job.tar.gz"), jobImplUri).get();
    } catch (Exception e) {
      e.printStackTrace();
      throw new InternalException("Could not download job implementation '" + _impl + "' from '" + uri + "'", e);
    }
  }

  private ListenableFuture<List<S3File>> downloadInput(Data input) throws InternalException {
    log("Downloading input '" + input.toString() + "'");
    URI inputUri;
    try {
      inputUri = com.logicblox.s3lib.Utils.getURI(input.getLocation());
    } catch (URISyntaxException e) {
      throw new InternalException("Invalid URI '" + input, e);
    }

    // Strip trailing slash
    String last = new File(inputUri.getPath()).toString();
    // Use the last part of the URL
    last = last.substring(last.lastIndexOf('/') + 1);

    File f = new File(_inputPath, last);
    try {
      if (input.getLocation().endsWith("/"))
        return _client.downloadDirectory(f, inputUri, true, true);
      else {
        List<ListenableFuture<S3File>> l = new ArrayList();
        l.add(_client.download(f, inputUri));
        return Futures.successfulAsList(l);
      }
    } catch (Exception e) {
      return Futures.immediateFailedFuture(new InternalException("Error downloading input " + input.getLocation()));
    }
  }

  private List<S3File> uploadOutput() throws UploadOutputFailedException {
    try {
      log("Uploading output...");
      return _client.uploadDirectory(_outputPath, _output, _outputEncryptionKey).get();
    } catch (Exception e) {
      throw new UploadOutputFailedException("Error uploading output files to " + _output, e);
    }
  }

  private boolean previousLogExists() throws InternalException {
    ObjectMetadata log = null;
    try {
      log = _client.exists(_s3Bucket, String.format("jobs/%s/log", _id)).get();
      return (log != null);
    } catch (Exception e) {
      throw new InternalException("Could not determine if log file already exists in S3.", e);
    }
  }

  private boolean jobCancelled() throws InternalException {
    ObjectMetadata cancelled = null;
    try {
      cancelled = _client.exists(_s3Bucket, String.format("jobs/%s/cancelled", _id)).get();
      return (cancelled != null);
    } catch (Exception e) {
      throw new InternalException("Could not determine if job was cancelled.", e);
    }
  }

  private void teardown() throws InternalException {
    log("Tearing down...");

    // TODO rework to make sure we don't overwrite uploaded results
    // from different jobs (moved this out to avoid reporting success
    // before upload)
    if (!previousLogExists()) {
      if (_drv != null) {
        File logPath = new File(Utils.nixLogPath(_drv));

        if (_killed) {
          log("Job was killed, not uploading log file.");
        } else if (!logPath.exists()) {
          log("No log file found, going on.");
        } else {
          // upload logs
          try {
            log("Uploading log...[%s/%s]".format(logPath.toString(), _outputLog));
            _client.upload(logPath, _outputLog).get();
            if(_lbDeploymentLogsPath.exists()) {
              ProcessBuilder pb = new ProcessBuilder("tar", "-C", _lbDeploymentHomePath.toString(), "-czf", _lbLogsPath.toString(), "logs");
              Process p = pb.start();
              p.waitFor();
              p.destroy();
              if(_lbLogsPath.exists()) {
                log("Uploading LB logs...[%s/%s]".format(_lbLogsPath.toString(), _outputLbLogs));
                _client.upload(_lbLogsPath, _outputLbLogs).get();
              }
            }
          } catch (Exception e) {
            throw new InternalException("Error uploading log to " + _outputLog, e);
          }
        }
      }
    } else {
      log("ERROR: Found log file, probably means the job was executed elsewhere. Skipping upload of logs and results.");
    }

    // cleaning up directories
    log("Removing local in-/output...");
    cleanUp();
    _completed = true;
  }

  private void runJob() throws Exception {
    log("Checking for platform-releases.nix override.");

    ObjectMetadata platformOverride;
    try {
      String key = "override/platform-releases.nix";
      platformOverride = _client.exists(_s3Bucket, key).get();
      if (platformOverride != null) {
        _client.download(new File("/tmp/platform-releases.nix"), new URI(String.format("s3://%s/%s", _s3Bucket, key))).get();
        log("Downloaded override for platform-releases.nix.");
      }
    } catch (Exception e) {
      platformOverride = null;
      log("Could not check for platform-releases.nix override, skipping.");
    }

    log("Running the actual job.");

    ArrayList<String> args = new ArrayList<String>();
    args.add("nix-instantiate");
    args.add("<worker/nix/job.nix>");
    args.add("--argstr");
    args.add("platform_version");
    args.add(_metadata.containsKey("platform") ? _metadata.get("platform") : "3.10.15");
    args.add("--arg");
    args.add("dependencies");
    args.add(_metadata.containsKey("dependencies") ? "with (import <config/lib> {}).pkgs; ["+_metadata.get("dependencies").replace(",", " ")+"]" : "[]");
    if (platformOverride != null) {
      args.add("-I");
      args.add("platform-releases=/tmp/platform-releases.nix");
    }

    try {
      // determine .drv
      _drv = readFromStdout(args.toArray(new String[args.size()]));

      // build deps
      nixShell(_drv);

    } catch (Exception ex) {
      if ( !_metadata.containsKey("dependencies") ) {
        throw ex;
      }
      else {
        throw new JobFailedException("Unknown problem with one of the dependencies.");
      }
    }

    // build .drv
    nixStoreRealise(_drv);
  }

  public String readFromStdout(String... args) throws Exception {
    ProcessBuilder pb = new ProcessBuilder(args);

    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0) {
      throw new Exception("Failed with exit code " + exit + "\n\n" + Utils.streamToString(p.getErrorStream()));
    }

    String res = Utils.streamToString(p.getInputStream());
    p.destroy();

    return res;
  }

  public void nixShell(String file) throws Exception {
    CommandLine commandLine = new CommandLine("nix-shell");
    commandLine.addArgument(file);
    commandLine.addArgument("--command");
    commandLine.addArgument("echo");

    Executor executor = new DefaultExecutor();
    executor.setExitValues(null);
    _shellDir.mkdirs();
    executor.setWorkingDirectory(_shellDir);

    int exit;
    try {
      exit = executor.execute(commandLine);
    } catch (Exception ex) {
      throw new InternalException("Execute exception: " + ex.getMessage(), ex);
    }

    if (exit != 0) {
      throw new JobFailedException("Could not build one of the dependencies." + exit);
    }
  }

  public void nixStoreRealise(String file) throws Exception {
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
    try {
      exit = executor.execute(commandLine);
    } catch (Exception ex) {
      throw new InternalException("Execute exception: " + ex.getMessage(), ex);
    }

    if (exit != 0) {
      File logPath = new File(Utils.nixLogPath(file));
      if (_timedOut) {
        throw new JobTimedOutException();
      } else if (_internalError != null) {
        throw new InternalException(_internalError);
      } else if (_diskFull) {
        throw new DiskFullException();
      } else if (exit == 1) {
        throw new JobKilledException();
      } else if (logPath.exists()) {
        if(_jobExitCode == 137) {
          throw new JobFailedException("Job was killed, most likely due to memory shortage");
        } else {
          throw new JobFailedException("Job failed" + ( _jobExitCode != 0 ? " with exit code " + _jobExitCode : ""));
        }
      } else {
        throw new InternalException("One of the dependencies of the job likely failed, as no log was found.");
      }
    }
  }

  public long getTimeout() {
    return _timeout;
  }

  public void setTimedOut() {
    _timedOut = true;
  }

  public void setDiskFull() {
    _diskFull = true;
  }

  public void setInternalError(String msg) {
    _internalError = msg;
  }

  public void setJobExitCode(int exit) {
    _jobExitCode = exit;
  }

  public boolean wasKilled() {
    return _killed;
  }

  public boolean hasCompleted() {
    return _completed;
  }

  public boolean hasBeenCancelled() {
    return _cancelled;
  }

  public void updateMaxDiskUsage() {
    long currentUsage = _diskFreeStart - _inputPath.getFreeSpace();
    if(_maxDiskUsage < currentUsage) {
      _maxDiskUsage = currentUsage;
    }
  }
}
