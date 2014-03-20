package com.logicblox.steve.worker;

public class InternalException extends Exception {
  public InternalException(String message, Throwable cause)
  {
    super(message, cause);
  }

  public InternalException(String message)
  {
    super(message);
  }
}
