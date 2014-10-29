package com.logicblox.steve.worker;

public class JobFailedException extends Exception {
  public JobFailedException(String message) {
    super(message);
  }
}
