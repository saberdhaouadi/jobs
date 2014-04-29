package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSReceivedMessage;
import com.logicblox.sqs.SQSQueueHandle;

import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.db.Database;

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
          processStatus(msg.getBody());
        }
        
        // wait if there were no messages
        if(messages.size() == 0)
        {
          Thread.sleep(10 * 1000);
        }
      }
      catch(Exception exc)
      {
        exc.printStackTrace();
      }
    }
  }

  private void processStatus(String status)
  {
    Backend.JobStatus.Builder builder = Backend.JobStatus.newBuilder();
    try
    {
      JsonFormat format = new JsonFormat(JsonFormat.LOOSE);
      format.merge(new ByteArrayInputStream(status.getBytes()), builder);
    }
    catch(IOException exc)
    {
      // should not be possible
      throw new RuntimeException(exc);
    }

    System.out.println(builder.build().toString());
  }
}