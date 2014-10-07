package com.logicblox.steve.db;

/**
 * An immutable representation of the status of a job at some point in time. 
 */
public final class Status {
  
  /**
   * Possible states of the job.
   */
  public enum State {
    INITIAL, QUEUED, EXECUTING, PENDING_CANCEL, SUCCEEDED, FAILED
  }

  /**
   * Possible events.
   */
  public enum Event {
    QUEUED, STARTED, PROGRESS, UNRESPONSIVE, CANCELLED, SUCCEEDED, FAILED, KILLED, TIMEOUT
  };

  private final long _timestamp;
  private final Event _event;
  private final String _machine;
  private final String _message;
    
  /**
   * Construct an immutable status with this content. Values may be null.
   * 
   * @param timestamp
   * @param event
   * @param machine
   * @param message
   */
  public Status(long timestamp, Event event, String machine, String message) {
    this._timestamp = timestamp;
    this._event = event;
    this._machine = machine;
    this._message = message;
  }

  public long getTimestamp() {
    return _timestamp;
  }
  
  public Event getEvent() {
    return _event;
  }

  public String getMachine() {
    return _machine;
  }

  public String getMessage() {
    return _message;
  }
  
  public boolean hasMessage() {
    return null != _message;
  }

  /**
   * Collector of contents to build an immutable status object.
   */
  public static class StatusBuilder {
  
    public long timestamp = -1;
    public Event event = null;
    public String machine = null;
    public String message = null;
    
    public Status build() {
      return new Status(timestamp, event, machine, message);
    }
  }
}