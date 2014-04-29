package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;

import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.db.Database;

import java.util.concurrent.atomic.AtomicBoolean;

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
          String body = msg.getBody();
        }
        
        // wait 30 seconds if there were no messages
        if(messages.size() == 0)
        {
          Thread.sleep(30 * 1000);
        }
      }
      catch(Exception exc)
      {
        exc.printStackTrace();
      }
    }
  }
}