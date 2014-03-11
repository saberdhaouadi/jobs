package com.logicblox.steve.worker;

import org.apache.commons.exec.LogOutputStream;

public class SteveJobLogHandler extends LogOutputStream {
  private String job;

  public SteveJobLogHandler(String job) {
    this.job = job;
  }

  @Override
  protected void processLine(String line, int level) {
    System.out.println(String.format("%s: %s", job, line));
  }

}
