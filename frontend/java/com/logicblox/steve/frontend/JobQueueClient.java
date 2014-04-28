package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.steve.db.Job;

import com.google.common.base.Functions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

public class JobQueueClient
{
  private SQSClient _sqs;
  private SQSQueueHandle _queue;

  public JobQueueClient(SQSClient sqs, SQSQueueHandle queue)
  {
    if(sqs == null)
      throw new IllegalArgumentException("queue client must be non-null");
    if(queue == null)
      throw new IllegalArgumentException("queue handle must be non-null");


    _sqs = sqs;
    _queue = queue;
  }

  /**
   * Returns a future of the unmodified job object on successful
   * submission the queue.
   */
  public ListenableFuture<Job> submit(Job job)
  {
    if(job == null)
      throw new IllegalArgumentException("job must be non-null");

    String msg = null;
    return Futures.transform(_sqs.send(_queue, msg), Functions.constant(job));
  }
}