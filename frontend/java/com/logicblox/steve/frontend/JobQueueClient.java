package com.logicblox.steve.frontend;

import java.util.Map;

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

/**
 * A client that submits job requests to a queue for workers to consume.
 */
public class JobQueueClient
{
  /**
   * Client used to communicate with the queue.
   */
  private final SQSClient _sqs;
  
  /**
   * Identification of the queue to target.
   */
  private final SQSQueueHandle _queue;

  /**
   * Construct a job client that will use this client to talk to this queue.
   * 
   * @param sqs
   * @param queue
   */
  public JobQueueClient(SQSClient sqs, SQSQueueHandle queue) {
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
  public ListenableFuture<Job> submit(Job job) {
    if(job == null)
      throw new IllegalArgumentException("job must be non-null");

    final Backend.RunJob.Builder request = Backend.RunJob.newBuilder()
      // TODO include ETag of implementation
      .setJobImpl(job.jobImplArchive)
      .setJob(job.id)
      .setOutput(job.outputPrefix);

    if(job.metadata.containsKey("timeout"))
      request.setTimeout(Integer.parseInt(job.metadata.get("timeout")));

    for(Data d : job.inputData)
      request.addInput(Conversions.convertDataToBackendFile(d));

    for(Map.Entry<String, String>  pair : job.metadata.entrySet())
      request.addMetadata(Conversions.createBackendParam(pair.getKey(), pair.getValue()));
    
    final String msg = new JsonFormat().printToString(request.build());

    System.err.println("submitting to " + _queue.getQueueUrl() + ":");
    System.err.println(msg);

    return Futures.transform(_sqs.send(_queue, msg), Functions.constant(job));
  }
}
