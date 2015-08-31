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

    if (line.endsWith("free disk space") {
      _job.setDiskFull();
    }

    // update max disk usage
    _job.updateMaxDiskUsage();
  }
}
