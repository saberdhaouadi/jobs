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
    if(queueName == null)
      throw new IllegalArgumentException("Queue name must not be null");
    if(queueUrl == null)
      throw new IllegalArgumentException("Queue url must not be null");

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
  
  @Override
  public String toString()
  {
    return _queueUrl;
  }

  @Override
  public int hashCode()
  {
    return getQueueUrl().hashCode();
  }

  public boolean equals(Object o)
  {
    if (o == null)
      return false;
    if (o == this)
      return true;
    if (!(o instanceof SQSQueueHandle))
      return false;
    
    SQSQueueHandle h = (SQSQueueHandle) o;
    return getQueueUrl().equals(h.getQueueUrl());
  }
}
