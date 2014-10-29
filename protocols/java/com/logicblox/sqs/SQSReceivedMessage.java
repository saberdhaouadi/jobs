package com.logicblox.sqs;

import java.util.Map;

import com.amazonaws.services.sqs.model.Message;

/**
 * Simple SQS message wrapper to prevent direct exposure to the AWS API model.
 */
public class SQSReceivedMessage {
  private final Message _sqsMessage;
  private final SQSQueueHandle _handle;

  public SQSReceivedMessage(SQSQueueHandle handle, Message sqsMessage) {
    if (handle == null)
      throw new IllegalArgumentException("Handle cannot be null");
    if (sqsMessage == null)
      throw new IllegalArgumentException("Message cannot be null");

    _handle = handle;
    _sqsMessage = sqsMessage;
  }

  public SQSQueueHandle getQueue() {
    return _handle;
  }

  public String getBody() {
    return _sqsMessage.getBody();
  }

  public String getId() {
    return _sqsMessage.getMessageId();
  }

  public Map<String, String> getAttributes() {
    return _sqsMessage.getAttributes();
  }

  public String getReceiptHandle() {
    return _sqsMessage.getReceiptHandle();
  }

  @Override
  public String toString() {
    return getId();
  }
}
