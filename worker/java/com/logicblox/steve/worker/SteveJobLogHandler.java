package com.logicblox.steve.worker;

import org.apache.commons.exec.LogOutputStream;

public class SteveJobLogHandler extends LogOutputStream
{
  private SteveJob _job;

  public SteveJobLogHandler(SteveJob job)
  {
    _job = job;
  }

  @Override
  protected void processLine(String line, int level)
  {
    _job.log(line);

    // support pdxscience's 'Executing stage' temporarily as status message
    if (line.startsWith("Executing stage: "))
    {
      _job._outgoing.notifyStatus(line);
    }
  }
}
