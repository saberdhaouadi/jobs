package com.logicblox.steve.frontend;

import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;

import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.db.Database;

public class StatusQueueClient
{
  private SQSClient _sqs;
  private SQSQueueHandle _queue;
  private Database _db;

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
}