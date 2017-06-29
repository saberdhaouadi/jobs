package com.logicblox.steve.worker;

import org.apache.commons.exec.LogOutputStream;

public class SteveJobLogHandler extends LogOutputStream {
  private SteveJob _job;
  private String PREFIX = "PROGRESS:";
  private String INTERNAL_ERROR_PREFIX = "INTERNAL_ERROR:";

  public SteveJobLogHandler(SteveJob job) {
    _job = job;
  }

  @Override
  protected void processLine(String line, int level) {
    _job.log(line);

    // support pdxscience's 'Executing stage' temporarily as status message
    if (line.startsWith("Executing stage: ")) {
      _job._outgoing.notifyStatus(line);
    } else if (line.startsWith(PREFIX)) {
      _job._outgoing.notifyStatus(line.substring(PREFIX.length()).trim());
    } else if (line.startsWith(INTERNAL_ERROR_PREFIX)) {
      _job.setInternalError(line.substring(INTERNAL_ERROR_PREFIX.length()).trim());
    }

    if (line.endsWith("timed out after " + _job.getTimeout() + " seconds")) {
      _job.setTimedOut();
    }

    if (line.endsWith("caused by lack of free disk space")) {
      _job.setDiskFull();
    }

    if (line.contains("failed with exit code")) {
      int i = line.lastIndexOf(' ');
      try {
        _job.setJobExitCode(Integer.parseInt(line.substring(i+1)));
      }
      catch(NumberFormatException e) {
        _job.log("Could not parse exit code of job execution: "+line);
      }
    }

    // update max disk usage
    _job.updateMaxDiskUsage();
  }
}
