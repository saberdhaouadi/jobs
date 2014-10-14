package com.logicblox.steve.common;

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

  public final long timestamp;
  public final Event event;
  public final String machine;
  public final String message;
    
  /**
   * Construct an immutable status with this content. Values may be null.
   * 
   * @param timestamp
   * @param event
   * @param machine
   * @param message
   */
  public Status(long timestamp, Event event, String machine, String message) {
    this.timestamp = timestamp;
    this.event = event;
    this.machine = machine;
    this.message = message;
  }

  public boolean hasMessage() {
    return null != message;
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