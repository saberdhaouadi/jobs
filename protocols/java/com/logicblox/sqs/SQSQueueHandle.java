package com.logicblox.sqs;

public class SQSQueueHandle
{
  private final String _queueName;
  private final String _queueUrl;

  /**
   * Create a handle for an amazon queue with this name and URL.
   *
   * @param queueName
   * @param queueUrl
   */
  public SQSQueueHandle(String queueName, String queueUrl)
  {
    _queueName = queueName;
    _queueUrl = queueUrl;
  }

  public final String getQueueUrl()
  {
    return _queueUrl;
  }

  public final String getQueueName()
  {
    return _queueName;
  }
  
  public String toString()
  {
    return _queueUrl;
  }
}
