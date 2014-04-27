package com.logicblox.steve.db;

public class Status
{
  public enum Code {
    INITIAL, QUEUE, RUNNING, CANCELLING, SUCCEEDED, FAILED, CANCELLED
  };

  private long _timestamp;
  private Code _code;
  private String _machine;

  public void setTimestamp(long v)
  {
    _timestamp = v;
  }

  public long getTimestamp()
  {
    return _timestamp;
  }

  public void setCode(Code c)
  {
    _code = c;
  }

  public Code getCode()
  {
    return _code;
  }

  public void setMachine(String v)
  {
    _machine = v;
  }

  public String getMachine()
  {
    return _machine;
  }
}
