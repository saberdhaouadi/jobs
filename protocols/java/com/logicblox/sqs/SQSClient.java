package com.logicblox.sqs;

import java.net.URI;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;

import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDLogger;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

public final class SQSClient
{ 
  private final String _endpoint;
  private final ListeningExecutorService _executor;
  private final AmazonSQSClient _sqs;
  private final Logger _logger;

  // 300 seconds is current default and cannot yet be modified.
  private final int _defaultVisibilityTimeout = 300;

  /**
   * Create an amazon client that targets the queue listening at this endpoint
   * (the URL of the region).
   * 
   * @param endpoint
   *          the URL for the endpoint (the SQS region).
   * @param client
   *          an instance of AmazonSQSClient to be wrapped by this client
   */
  public SQSClient(String endpoint, AmazonSQSClient client, ListeningExecutorService executor)
  {
    // endpoint is passed in because client does not have a getEndPoint()
    _endpoint = endpoint;
    _executor = executor;
    _sqs = client;
    _logger = SystemDLogger.getLogger("SQSClient");
  }

  public final String getEndpoint()
  {
    return _endpoint;
  }

  public static String getEndpoint(URI q)
  {
    return q.getScheme() + "://" + q.getAuthority();
  }

  public static String getAccount(URI q)
  {
    String[] segments = q.getPath().split("/");
    return segments[1];
  }

  public static String getQueueName(URI q)
  {
    String[] segments = q.getPath().split("/");
    return segments[2];
  }

  public SQSQueueHandle getQueue(URI uri, boolean create)
  throws SQSException
  {
    String endpoint = getEndpoint(uri);
    String account = getAccount(uri);
    String name = getQueueName(uri);
    
    if (!_endpoint.equals(endpoint))
      throw new SQSException("Client can only use queues at '" + getEndpoint() + "', not '" + endpoint + "'");

    return getQueue(account, name, create);
  }

  public SQSQueueHandle getQueue(String queueName, boolean create)
  throws SQSException
  {
    return getQueue(null, queueName, create);
  }

  /**
   * Get the queue with this name in this account, creating if it does not exist and creation is requested.
   */
  public SQSQueueHandle getQueue(String account, String queueName, boolean create)
  throws SQSException
  {
    GetQueueUrlRequest request =
      new GetQueueUrlRequest()
      .withQueueName(queueName)
      .withQueueOwnerAWSAccountId(account);

    try
    {
      String queueUrl = _sqs.getQueueUrl(request).getQueueUrl();
      SQSQueueHandle handle = new SQSQueueHandle(queueName, queueUrl);
      return handle;
    }
    catch (AmazonServiceException exc)
    {
      String msg = "Queue '/" + account + "/" + queueName + "' does not exist or access denied: " + exc.getMessage();

      if ("AWS.SimpleQueueService.NonExistentQueue".equals(exc.getErrorCode()))
      {
        if (create)
        {
          SQSQueueHandle handle = createQueue(queueName);
          URI uri = URI.create(handle.getQueueUrl());
          String account2 = getAccount(uri);
          if (account != null && !account2.equals(account))
            throw new SQSException("Cannot make queues in other accounts. "
                + "Requested account for queue was '" + account + "', account of credentials is '"
                + account2 + "'");

          return handle;
        }
        else
        {
          _logger.error(msg, exc);
          throw new SQSException("Queue does not exist or access denied: " + exc.getMessage(), exc);
        }
      }
      else
      {
        _logger.error(msg, exc);
        throw new SQSException("Could not get queue with name '" + queueName + "'", exc);
      }
    }
  }

  /**
   * Create a queue.
   */
  public SQSQueueHandle createQueue(String queueName) throws SQSException
  {
    return createQueue(queueName, _defaultVisibilityTimeout);
  }

  public SQSQueueHandle createQueue(String queueName, int visibilityTimeout) throws SQSException
  {
    // make sure we create queues with 5mins visibility timeouts
    Map<String, String> attributes = new HashMap<String, String>();
    attributes.put("VisibilityTimeout", Integer.toString(visibilityTimeout));
    CreateQueueRequest request = new CreateQueueRequest()
      .withQueueName(queueName)
      .withAttributes(attributes);

    try
    {
      String queueUrl = _sqs.createQueue(request).getQueueUrl();
      return new SQSQueueHandle(queueName, queueUrl);

    }
    catch (AmazonServiceException exc)
    {
      String msg = "Could not create queue with name '" + queueName + "' " + exc.getMessage();
      _logger.error(msg, exc);
      throw new SQSException(msg, exc);
    }
  }

  /**
   * Asynchronously send message to queue.
   */
  public ListenableFuture<String> send(SQSQueueHandle handle, String msg)
  {
    if(handle == null)
      return Futures.immediateFailedFuture(new IllegalArgumentException("handle most not be null"));
    if(msg == null)
      return Futures.immediateFailedFuture(new IllegalArgumentException("message most not be null"));

    final SendMessageRequest request = new SendMessageRequest(handle.getQueueUrl(), msg);

    return _executor.submit(new Callable<String>() {
        public String call() throws Exception {
          return _sqs.sendMessage(request).getMessageId();
        }
      });
  }
}
