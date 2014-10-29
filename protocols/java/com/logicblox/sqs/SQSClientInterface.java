package com.logicblox.sqs;

import java.util.List;

import com.google.common.util.concurrent.ListenableFuture;

/**
 * TODO Note that methods that still need to be revised are currently
 * not part of this interface.
 */
public interface SQSClientInterface {
  /**
   * Asynchronously send message to queue.
   */
  public ListenableFuture<String> send(SQSQueueHandle handle, String msg);

  /**
   * Receive messages from the queue with this handle.
   *
   * @param handle
   * @return
   * @throws SQSException
   */
  public List<SQSReceivedMessage> receive(SQSQueueHandle handle) throws SQSException;

  /**
   * Delete message.
   * <p/>
   * Returns original message on successful delete. If deletion
   * failed, then the future will throw an exception.
   */
  public ListenableFuture<SQSReceivedMessage> delete(final SQSReceivedMessage msg);

  /**
   * Delete a list of messages.
   * <p/>
   * If a subset of the messages could not be deleted, then only the
   * futures of those specific messages throw an exception.
   */
  public List<ListenableFuture<SQSReceivedMessage>> delete(List<SQSReceivedMessage> messages);
}
