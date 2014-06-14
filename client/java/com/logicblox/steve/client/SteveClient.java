package com.logicblox.steve.client;

import java.util.List;
import java.util.UUID;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import com.logicblox.concurrent.MoreFutures;
import com.logicblox.concurrent.FutureTransform;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.client.ProtobufServiceClient;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.common.Option;
import com.logicblox.steve.protocol.Frontend;

/**
 * Client-side API for making calls to steve. This is used by the
 * lb-steve-client, but is also intended to be used in applications.
 */ 
public class SteveClient
{
  private ProtobufServiceClient _client;

  public SteveClient(ProtobufServiceClient client)
  {
    _client = client;
  }

  /**
   * Create a new job, returns an asynchronous job id.
   */
  public ListenableFuture<String> createJob(String jobImpl, List<Frontend.File> inputs, String output)
  throws ServiceClientException
  {
    // TODO retry on connection issues with the same clientId
    String clientId = UUID.randomUUID().toString();
      
    Frontend.JobCreateRequest.Builder createReq = 
      Frontend.JobCreateRequest.newBuilder()
      .setClientId(clientId)
      .setJobImpl(jobImpl)
      .setOutput(output);
    
    for(Frontend.File input : inputs)
      createReq.addInput(input);

    Frontend.Request.Builder req =
      Frontend.Request.newBuilder()
      .setCreate(createReq);
    
    return Futures.transform(
      post(req.build()),
      new Function<Frontend.Response, String>()
      {        
        @Override
        public String apply(Frontend.Response response)
        {
          return response.getCreate().getJobId();
        }
      });
  }

  /**
   * Get the status of the specified job id.
   */
  public ListenableFuture<List<Frontend.Status>> getStatus(String id)
  throws ServiceClientException
  {
    Frontend.Request.Builder req =
      Frontend.Request.newBuilder()
      .setState(
        Frontend.StateRequest.newBuilder()
        .setId(id)
        .setDetail(true));

    return Futures.transform(
      post(req.build()),
      new Function<Frontend.Response, List<Frontend.Status>>()
      {        
        @Override
        public List<Frontend.Status> apply(Frontend.Response response)
        {
          return response.getState().getStatusList();
        }
      });
  }

  /**
   * Get the result of the specified job id.
   */
  public ListenableFuture<List<Frontend.File>> getResult(String id)
  throws ServiceClientException
  {
    Frontend.Request.Builder req =
      Frontend.Request.newBuilder()
      .setResult(
        Frontend.JobResultRequest.newBuilder()
        .setJobId(id));

    return Futures.transform(
      post(req.build()),
      new Function<Frontend.Response, List<Frontend.File>>()
      {        
        @Override
        public List<Frontend.File> apply(Frontend.Response response)
        {
          return response.getResult().getOutputList();
        }
      });
  }

  /**
   * Utility for the end-to-end posting of a request.
   */
  private ListenableFuture<Frontend.Response> post(Frontend.Request req)
  throws ServiceClientException
  {
    Frontend.Request.Builder reqB = Frontend.Request.newBuilder();
    Frontend.Response.Builder respB = Frontend.Response.newBuilder();

    ProtoBufExchange exchange = new ProtoBufExchange(reqB, respB, Option.<String>none());
    exchange.setRequestMessage(req);

    return instrumentForErrorHandling(exchange, _client.postMessage(exchange));
  }

  /**
   * Transforms future into a future that will throw ServiceException.
   */
  private static ListenableFuture<Frontend.Response> instrumentForErrorHandling(
    final ProtoBufExchange e1,
    ListenableFuture<ProtoBufExchange> future)
  {
    return MoreFutures.transform(
      future,
      new FutureTransform<ProtoBufExchange, Frontend.Response>()
      {
        @Override
        public ListenableFuture<Frontend.Response> transform(ProtoBufExchange e2)
        {
          try
          {
            Frontend.Response response = (Frontend.Response) e2.getResponseMessage();
            return Futures.immediateFuture(response);
          }
          catch(Exception exc)
          {
            return Futures.immediateFailedFuture(exc);
          }
        }

        @Override
        public ListenableFuture<Frontend.Response> create(Throwable t)
        {
          if(t instanceof ServiceClientException)
          {
            ServiceClientException exc = (ServiceClientException) t;

            // If we manage to get a response from the exchange, then
            // throw that as a nice exception.
            Frontend.Response response = null;
            try
            {
              response = (Frontend.Response) e1.getResponseMessage();
            }
            catch(Exception e)
            {
              // on purpose ignore all exceptions. We'll just rethrow
              // the original exception.
            }

            if(response != null)
              return Futures.immediateFailedFuture(
                new SteveClientException(exc.getMessage(), exc.getStatus(), response));
          }

          return Futures.immediateFailedFuture(t);
        }
      });
  }
}
