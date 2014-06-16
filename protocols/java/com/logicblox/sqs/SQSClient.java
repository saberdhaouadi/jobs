package com.logicblox.sqs;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;

import com.amazonaws.AmazonServiceException;
import com.amazonaws.services.sqs.AmazonSQSClient;
import com.amazonaws.services.sqs.model.CreateQueueRequest;
import com.amazonaws.services.sqs.model.GetQueueUrlRequest;
import com.amazonaws.services.sqs.model.SendMessageRequest;
import com.amazonaws.services.sqs.model.ReceiveMessageRequest;
import com.amazonaws.services.sqs.model.DeleteMessageRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequest;
import com.amazonaws.services.sqs.model.DeleteMessageBatchRequestEntry;
import com.amazonaws.services.sqs.model.DeleteMessageBatchResult;
import com.amazonaws.services.sqs.model.DeleteMessageBatchResultEntry;
import com.amazonaws.services.sqs.model.BatchResultErrorEntry;
import com.amazonaws.services.sqs.model.Message;

import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDLogger;

import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

public final class SQSClient implements SQSClientInterface
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

  public List<SQSReceivedMessage> receive(SQSQueueHandle handle)
  throws SQSException
  {
    return receive(handle, 10);
  }

  public List<SQSReceivedMessage> receive(SQSQueueHandle handle, int maxMessages)
  throws SQSException
  {
    return receive(handle, maxMessages, false);
  }

  public List<SQSReceivedMessage> receiveWithAttributes(SQSQueueHandle handle, int maxMessages)
  throws SQSException
  {
    return receive(handle, maxMessages, true);
  }

  /**
   * Get messages from the queue identified by this handle.
   * 
   * @param handle
   *          the identification of the queue from which to get messages.
   * @param maxMessages
   *          the maximum number of messages to retrieve.
   * @param attributes
   *          whether to get messages with extra attributes.
   * @return a list with the received messages (never null).
   */
  protected List<SQSReceivedMessage> receive(final SQSQueueHandle handle, int maxMessages, boolean attributes)
  throws SQSException
  {
    // create request to check messages on the queue
    final ReceiveMessageRequest request = new ReceiveMessageRequest(handle.getQueueUrl());
    request.setMaxNumberOfMessages(maxMessages);
    if (attributes)
      request.setAttributeNames(Collections.singletonList("All"));

    // get messages from sqs.
    List<Message> messages;

    try
    {
      messages = _sqs.receiveMessage(request).getMessages();
    }
    catch (AmazonServiceException e)
    {
      throw new SQSException("Could not receive messages from queue " + handle.getQueueUrl(), e);
    }

    List<SQSReceivedMessage> result = new ArrayList<SQSReceivedMessage>();
    if (messages.size() > 1)
    {
      // Oddly, a single request can actually return the same message
      // multiple times. This is also caught later when processing
      // messages, but we immediately remove duplicates here as well.
      final Set<String> ids = new HashSet<String>();
      for (int i = messages.size() - 1; i >= 0; i--)
      {
        Message m = messages.get(i);
        String id = m.getMessageId();
        if (ids.contains(id))
          continue;
        
        ids.add(id);
        result.add(new SQSReceivedMessage(handle, m));
      }
    }
    else
    {
      for (Message m : messages)
        result.add(new SQSReceivedMessage(handle, m));
    }
    
    return result;
  }

  /**
   * Delete message.
   *
   * Returns original message on successful delete.
   */
  public ListenableFuture<SQSReceivedMessage> delete(final SQSReceivedMessage msg)
  {
    SQSQueueHandle handle = msg.getQueue();
    final DeleteMessageRequest request = new DeleteMessageRequest(handle.getQueueUrl(), msg.getReceiptHandle());

    return _executor.submit(new Callable<SQSReceivedMessage>()
    {
      public SQSReceivedMessage call() throws Exception
      {
        _sqs.deleteMessage(request);
        return msg;
      }
    });
  }

  /**
   * Delete a list of messages, possibly from different queues.
   *
   * If individual messages could not be deleted, then only that specific future will throw an exception.
   */
  public List<ListenableFuture<SQSReceivedMessage>> delete(List<SQSReceivedMessage> messages)
  {
    // Avoid overhead for simple cases
    if(messages.size() == 0)
      return Collections.emptyList();
    if(messages.size() == 1)
      return Collections.singletonList(delete(messages.get(0)));
    
    boolean allSameQueue = true;
    SQSQueueHandle hFirst = messages.get(0).getQueue();
    for(SQSReceivedMessage m : messages)
    {
      if(hFirst.equals(m.getQueue()))
      {
        allSameQueue = false;
        break;
      }
    }

    if(allSameQueue)
      return delete(hFirst, messages);

    // We need to make a request for each queue, and messages belong
    // to different queues. So first group messages by queue.
    Map<SQSReceivedMessage, Integer> originalIndexes =
      new HashMap<SQSReceivedMessage, Integer>();

    final Map<SQSQueueHandle, List<SQSReceivedMessage>> map = 
      new HashMap<SQSQueueHandle, List<SQSReceivedMessage>>();

    for(int i = 0; i < messages.size(); i++)
    {
      SQSReceivedMessage m = messages.get(i);
      SQSQueueHandle h = m.getQueue();

      if (!map.containsKey(h))
        map.put(h, new ArrayList<SQSReceivedMessage>());

      originalIndexes.put(m, i);
      map.get(h).add(m);
    }

    // Create result, all initialized to null.
    List<ListenableFuture<SQSReceivedMessage>> result =
      new ArrayList<ListenableFuture<SQSReceivedMessage>>();

    for(int i = 0; i < messages.size(); i++)
      result.add(null);

    // For every queue, make a batch delete call.
    for (SQSQueueHandle q : map.keySet())
    {
      List<SQSReceivedMessage> msgs = map.get(q);
      List<ListenableFuture<SQSReceivedMessage>> futures = delete(q, msgs);

      // Insert the futures at the correct positions in the overall result
      for(int i = 0; i < msgs.size(); i++)
      {
        SQSReceivedMessage m = msgs.get(i);
        int originalIndex = originalIndexes.get(m);
        result.set(originalIndex, futures.get(i));
      }
    }

    return result;
  }

  private List<ListenableFuture<SQSReceivedMessage>> delete(final SQSQueueHandle h, final List<SQSReceivedMessage> messages)
  {
    // Avoid overhead for simple cases
    if(messages.size() == 0)
      return Collections.emptyList();
    if(messages.size() == 1)
      return Collections.singletonList(delete(messages.get(0)));

    ListenableFuture<List<Object>> future = 
      _executor.submit(new Callable<List<Object>>()
      {
        public List<Object> call() throws Exception
        {
          try
          {
            return deleteSync(h, messages);
          }
          catch(Exception exc)
          {
            exc.printStackTrace();
            throw exc;
          }
        }
      });

    // Transform the Future<List> into a List<Future>
    List<ListenableFuture<SQSReceivedMessage>> result = new ArrayList<ListenableFuture<SQSReceivedMessage>>();
    for(int i = 0; i < messages.size(); i++)
    {
      final int index = i;
      result.add(
        Futures.transform(future, new AsyncFunction<List<Object>, SQSReceivedMessage>()
        {
          public ListenableFuture<SQSReceivedMessage> apply(List<Object> list)
          {
            Object current = list.get(index);
            if(current instanceof Exception)
              return Futures.immediateFailedFuture((Exception) current);
            else
              return Futures.immediateFuture((SQSReceivedMessage) current);
          }
        }));
    }

    return result;
  }

  /**
   * Returns a list of SQSException or SQSReceivedMessage.
   */
  private List<Object> deleteSync(SQSQueueHandle h, List<SQSReceivedMessage> messages)
  {
    // Create entries, verifying that messages belong to this queue
    List<DeleteMessageBatchRequestEntry> entries = new ArrayList<DeleteMessageBatchRequestEntry>();
    for(int i = 0; i < messages.size(); i++)
    {
      SQSReceivedMessage m = messages.get(i);
      if(!m.getQueue().equals(h))
        throw new IllegalArgumentException("Message does not belong to queue: " + m);

      entries.add(new DeleteMessageBatchRequestEntry(String.valueOf(i), m.getReceiptHandle()));
    }
    
    DeleteMessageBatchResult response = _sqs.deleteMessageBatch(
      new DeleteMessageBatchRequest(h.getQueueUrl(), entries));

    // Construct the result in the original order
    List<Object> result = new ArrayList<Object>(Collections.nCopies(messages.size(), null));
    for(BatchResultErrorEntry err : response.getFailed())
    {
      int originalIndex = Integer.parseInt(err.getId());
      result.set(originalIndex, new SQSException(err.toString()));
    }
    for(DeleteMessageBatchResultEntry err : response.getSuccessful())
    {
      int originalIndex = Integer.parseInt(err.getId());
      result.set(originalIndex, messages.get(originalIndex));
    }

    return result;
  }
}
