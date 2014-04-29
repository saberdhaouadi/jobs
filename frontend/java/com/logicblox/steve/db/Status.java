package com.logicblox.steve.db;

public class Status
{
  public enum State {
    INITIAL, QUEUED, EXECUTING, PENDING_CANCEL, SUCCEEDED, FAILED
  }

  public enum Event {
    EV_QUEUED, EV_STARTED, EV_PROGRESS, EV_UNRESPONSIVE, EV_CANCEL, EV_SUCCEEDED, EV_FAILED, EV_KILLED, EV_TIMEOUT
  };

  private long _timestamp;
  private Event _event;
  private String _machine;
  private String _message;

  public void setTimestamp(long v)
  {
    _timestamp = v;
  }

  public long getTimestamp()
  {
    return _timestamp;
  }

  public void setEvent(Event c)
  {
    _event = c;
  }

  public Event getEvent()
  {
    return _event;
  }

  public void setMachine(String v)
  {
    _machine = v;
  }

  public String getMachine()
  {
    return _machine;
  }

  public boolean hasMessage()
  {
    return _message != null;
  }
  
  public String getMessage()
  {
    return _message;
  }

  public void setMessage(String s)
  {
    _message = s;
  }
}
