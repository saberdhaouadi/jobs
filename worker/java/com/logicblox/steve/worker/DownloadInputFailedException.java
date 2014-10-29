package com.logicblox.steve.worker;

public class DownloadInputFailedException extends Exception {
  public DownloadInputFailedException(String message, Throwable cause) {
    super(message, cause);
  }

  public DownloadInputFailedException(String message) {
    super(message);
  }
}
