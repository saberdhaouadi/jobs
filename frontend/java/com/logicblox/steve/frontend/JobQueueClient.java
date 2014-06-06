package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.db.Job;
import com.logicblox.steve.protocol.Backend;

import com.google.common.base.Functions;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.googlecode.protobuf.format.JsonFormat;

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

    // TODO timeout
    Backend.RunJob.Builder request =
      Backend.RunJob.newBuilder()
      // TODO include ETag of implementation
      .setJobImpl(job.impl.archive.getLocation())
      .setJob(job.getId())
      .setOutput(job.getOutputPrefix());

    for(Data d : job.getInputData())
    {
      request.addInput(Conversions.convertDataToBackendFile(d));
    }
    
    String msg = new JsonFormat().printToString(request.build());
    return Futures.transform(_sqs.send(_queue, msg), Functions.constant(job));
  }

}