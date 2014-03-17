package com.logicblox.steve.worker;

import org.apache.commons.exec.LogOutputStream;

public class SteveJobLogHandler extends LogOutputStream {
  private SteveJob job;

  public SteveJobLogHandler(SteveJob job) {
    this.job = job;
  }

  @Override
  protected void processLine(String line, int level) {
    job.log(line);

    // support pdxscience's 'Executing stage' temporarily as status message
    if (line.startsWith("Executing stage: "))
    {
      job.outgoing.notifyStatus(line);
    }
  }

}
