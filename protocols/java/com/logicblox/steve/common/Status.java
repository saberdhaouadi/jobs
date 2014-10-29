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
  }

  ;

  public final Long timestamp;
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

  @Override
  public String toString() {
    return "@" + timestamp + " " + event + " in '" + machine + "': " + message;
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((event == null) ? 0 : event.hashCode());
    result = prime * result + ((machine == null) ? 0 : machine.hashCode());
    result = prime * result + ((message == null) ? 0 : message.hashCode());
    result = prime * result + ((timestamp == null) ? 0 : timestamp.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Status other = (Status) obj;
    if (event != other.event)
      return false;
    if (machine == null) {
      if (other.machine != null)
        return false;
    } else if (!machine.equals(other.machine))
      return false;
    if (message == null) {
      if (other.message != null)
        return false;
    } else if (!message.equals(other.message))
      return false;
    if (timestamp == null) {
      if (other.timestamp != null)
        return false;
    } else if (!timestamp.equals(other.timestamp))
      return false;
    return true;
  }


}