package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSReceivedMessage;
import com.logicblox.sqs.SQSQueueHandle;

import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.db.Status;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.googlecode.protobuf.format.JsonFormat;

public class StatusQueueClient
{
  private SQSClient _sqs;
  private SQSQueueHandle _queue;
  private Database _db;

  private AtomicBoolean _terminate = new AtomicBoolean(false);

  public StatusQueueClient(SQSClient sqs, SQSQueueHandle queue, Database db)
  {
    if(sqs == null)
      throw new IllegalArgumentException("queue client must be non-null");
    if(queue == null)
      throw new IllegalArgumentException("queue handle must be non-null");
    if(db == null)
      throw new IllegalArgumentException("db must be non-null");

    _sqs = sqs;
    _queue = queue;
    _db = db;
  }

  public void start()
  {
    Thread t = new Thread(
      new Runnable()
      {
        public void run()
        {
          loop();
        }
      });
    t.start();
  }

  private void loop()
  {
    while(!_terminate.get())
    {
      try
      {
        List<SQSReceivedMessage> messages = _sqs.receive(_queue);

        for(SQSReceivedMessage msg : messages)
        {
          try
          {
            processStatus(msg.getBody());
          }
          catch(Exception exc)
          {
            exc.printStackTrace();
          }
        }

        _sqs.delete(messages);
        
        // wait if there were no messages
        if(messages.size() == 0)
        {
          Thread.sleep(5 * 1000);
        }
      }
      catch(Exception exc)
      {
        exc.printStackTrace();
      }
    }
  }

  private void processStatus(String statusString)
  {
    System.err.println("[status] " + statusString);

    Backend.JobStatus.Builder builder = Backend.JobStatus.newBuilder();
    try
    {
      JsonFormat format = new JsonFormat(JsonFormat.LOOSE);
      format.merge(new ByteArrayInputStream(statusString.getBytes()), builder);
    }
    catch(IOException exc)
    {
      // should not be possible
      throw new RuntimeException(exc);
    }

    Backend.JobStatus protoStatus = builder.build();

    Status status = new Status();
    status.setMachine(protoStatus.getMachine());
    status.setTimestamp(protoStatus.getTimestamp());

    switch(protoStatus.getStatusCode())
    {
      case STARTED:
      {
        status.setEvent(Status.Event.STARTED);
        break;
      }
      case PROGRESS:
      {
        status.setEvent(Status.Event.PROGRESS);
        if(protoStatus.hasProgressDetails())
          status.setMessage(protoStatus.getProgressDetails().getMessage());
        break;        
      }
      case SUCCEEDED:
      {
        status.setEvent(Status.Event.SUCCEEDED);
        if(protoStatus.hasSucceededDetails())
        {
          List<Data> output = Conversions.convertFileToData(protoStatus.getSucceededDetails().getOutputList());
          _db.setResult(protoStatus.getJob(), output);
        }
        break;        
      }
      case FAILED:
      {
        status.setEvent(Status.Event.FAILED);
        if(protoStatus.hasFailedDetails())
        {
          Backend.FailedDetails d = protoStatus.getFailedDetails();
          status.setMessage(
            (d.hasErrorCode() ? d.getErrorCode() + ": " : "") +
            (d.hasErrorMessage() ? d.getErrorMessage() : ""));
        }
        break;
      }
      default:
        System.err.println("error: status not yet supported");
    }

    _db.addStatus(protoStatus.getJob(), status);
  }
}