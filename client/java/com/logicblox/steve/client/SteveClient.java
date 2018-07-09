package com.logicblox.steve.client;

import java.net.URI;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.logicblox.concurrent.FutureTransform;
import com.logicblox.concurrent.MoreFutures;
import com.logicblox.cloudstore.ExpBackoffRetryPolicy;
import com.logicblox.cloudstore.ThrowableRetriableTask;
import com.logicblox.cloudstore.ThrowableRetryPolicy;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.protocol.Frontend;
import com.logicblox.web.client.service.JsonServiceExchangeFactory;

import com.logicblox.web.client.service.ServiceClient;
import com.logicblox.web.client.service.ServiceClientException;
import com.logicblox.web.client.service.ServiceClientOptions;
import com.logicblox.web.client.service.ServiceExchange;
import com.logicblox.web.common.http.HttpMethod;

/**
 * Client-side API for making calls to steve. This is used by the
 * lb-steve-client, but is also intended to be used in applications.
 */
public class SteveClient implements SteveClientInterface {
  private ServiceClient _client = new ServiceClient();

  private ServiceClientOptions _options;
  private URI _serviceUri;
  private ListeningScheduledExecutorService _scheduler;
  
  public SteveClient(URI serviceUri, ServiceClientOptions options, ScheduledExecutorService scheduler) {
    _options = options;
    _serviceUri = serviceUri;
    if (scheduler != null)
      _scheduler = MoreExecutors.listeningDecorator(scheduler);
  }

  /**
   * Create a new job, returns an asynchronous job id.
   */
  public ListenableFuture<String> createJob(
          String jobImpl,
          Iterable<Frontend.File> inputs,
          URI outputPrefix,
          String outputEncryptionKey,
          Iterable<Frontend.Param> metadata)
          throws ServiceClientException {
    // TODO retry on connection issues with the same clientId
    String clientId = UUID.randomUUID().toString();

    Frontend.JobCreateRequest.Builder createReq =
            Frontend.JobCreateRequest.newBuilder()
                    .setClientId(clientId)
                    .setJobImpl(jobImpl)
                    .setOutput(outputPrefix.toString());

    if (outputEncryptionKey != null && ! "".equals(outputEncryptionKey)) {
      createReq.setOutputEncryptionKey(outputEncryptionKey);
    }

    for (Frontend.File input : inputs)
      createReq.addInput(input);

    for (Frontend.Param param : metadata)
      createReq.addMetadata(param);

    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setCreate(createReq);

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, String>() {
              @Override
              public String apply(Frontend.Response response) {
                return response.getCreate().getJobId();
              }
            });
  }

  /**
   * Get the status of the specified job id.
   */
  public ListenableFuture<Frontend.State> getState(String id)
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setState(
                            Frontend.StateRequest.newBuilder()
                                    .setId(id)
                                    .setDetail(true));

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, Frontend.State>() {
              @Override
              public Frontend.State apply(Frontend.Response response) {
                return response.getState().getState();
              }
            });
  }

  /**
   * Get the log of the specified job id.
   */
  public ListenableFuture<String> getLog(String id)
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setLog(
                            Frontend.JobLogRequest.newBuilder()
                                    .setJobId(id));

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, String>() {
              @Override
              public String apply(Frontend.Response response) {
                return response.getLog().getLog();
              }
            });
  }

  /**
   * Get the LB services logs of the specified job id.
   */
  public ListenableFuture<String> getLBLogs(String id, URI destination)
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setLbLogs(
                            Frontend.JobLBLogsRequest.newBuilder()
                                    .setDestination(destination.toString())
                                    .setId(id));

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, String>() {
              @Override
              public String apply(Frontend.Response response) {
                return id;
              }
            });
  }

  /**
   * Get the result of the specified job id.
   */
  public ListenableFuture<List<Frontend.File>> getResult(String id)
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setResult(
                            Frontend.JobResultRequest.newBuilder()
                                    .setJobId(id));

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<Frontend.File>>() {
              @Override
              public List<Frontend.File> apply(Frontend.Response response) {
                return response.getResult().getOutputList();
              }
            });
  }

  /**
   * Wait for completion of a job id, with a fixed delay (see interface for more docs)
   */
  public ListenableFuture<Frontend.State> wait(final String id, final long pollDelaySeconds, final StateNotify notify) {
    return waitInternal(0, id, pollDelaySeconds, notify);
  }

  private ListenableFuture<Frontend.State> waitInternal(
          final int count, final String id, final long pollDelaySeconds, final StateNotify notify) {
    // TODO extend to accept temporary connectivity issues while waiting
    return Futures.dereference(
            _scheduler.schedule(
                    new Callable<ListenableFuture<Frontend.State>>() {
                      public ListenableFuture<Frontend.State> call() throws ServiceClientException {
                        return Futures.transform(
                                getState(id),
                                new AsyncFunction<Frontend.State, Frontend.State>() {
                                  public ListenableFuture<Frontend.State> apply(Frontend.State state) {
                                    if (notify != null) {
                                      try {
                                        notify.notify(state);
                                      } catch (Exception exc) {
                                      }
                                    }

                                    if (Conversions.isComplete(state))
                                      return Futures.immediateFuture(state);
                                    else
                                      return waitInternal(count + 1, id, pollDelaySeconds, notify);
                                  }
                                });
                      }
                    },
                    // do not delay initial execution
                    (count == 0 ? 0 : pollDelaySeconds),
                    TimeUnit.SECONDS));
  }

  public ListenableFuture<List<Frontend.File>> waitForJob(
          final String id,
          final long pollDelaySeconds,
          final StateNotify notify) {
    return Futures.transform(
            wait(id, pollDelaySeconds, notify),
            new AsyncFunction<Frontend.State, List<Frontend.File>>() {
              @Override
              public ListenableFuture<List<Frontend.File>> apply(Frontend.State state) throws Exception {
                if ("SUCCEEDED".equals(state.getState()))
                  return getResult(id);
                else
                  return Futures.immediateFailedFuture(
                          new SteveClientException(
                                  _serviceUri.toString(),
                                  "Job '" + id + "' failed and has no output",
                                  "JOB_FAILED"));
              }
            });
  }

  /**
   * Asynchronously upload a new job implementation.
   */
  public ListenableFuture<String> addJobImpl(
          String jobImpl,
          Frontend.File archive,
          Iterable<Frontend.Param> metadata)
          throws ServiceClientException {
    // TODO retry on connection issues with the same clientId
    String clientId = UUID.randomUUID().toString();

    Frontend.ImplAddRequest.Builder addReq =
            Frontend.ImplAddRequest.newBuilder()
                    .setClientId(clientId)
                    .setId(jobImpl)
                    .setImplementation(archive);

    for (Frontend.Param param : metadata)
      addReq.addMetadata(param);

    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setImplAdd(addReq);

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, String>() {
              @Override
              public String apply(Frontend.Response response) {
                return response.getImplAdd().getId();
              }
            });
  }

  /**
   * List job implementations
   */
  public ListenableFuture<List<Frontend.JobImplInfo>> getJobImplList()
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setImplList(Frontend.ImplListRequest.newBuilder());

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<Frontend.JobImplInfo>>() {
              @Override
              public List<Frontend.JobImplInfo> apply(Frontend.Response response) {
                return response.getImplList().getJobImplList();
              }
            });
  }

  /**
   * List Queues
   */
  public ListenableFuture<List<String>> getQueues()
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setListQueues(Frontend.ListQueuesRequest.newBuilder());

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<String>>() {
              @Override
              public List<String> apply(Frontend.Response response) {
                return response.getListQueues().getQueueList();
              }
            });
  }

  /**
   * List platforms
   */
  public ListenableFuture<List<String>> getPlatforms()
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setListPlatforms(Frontend.ListPlatformsRequest.newBuilder());

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<String>>() {
              @Override
              public List<String> apply(Frontend.Response response) {
                return response.getListPlatforms().getPlatformList();
              }
            });
  }

  /**
   * List metadata keys
   */
  public ListenableFuture<List<String>> getMetadataKeys()
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setListMetadataKeys(Frontend.ListMetadataKeysRequest.newBuilder());

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<String>>() {
              @Override
              public List<String> apply(Frontend.Response response) {
                return response.getListMetadataKeys().getKeyList();
              }
            });
  }

  /**
   * List metadata values
   */
  public ListenableFuture<List<String>> getMetadataValues(String key)
          throws ServiceClientException {
    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setListMetadataValues(Frontend.ListMetadataValuesRequest.newBuilder().setKey(key));

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, List<String>>() {
              @Override
              public List<String> apply(Frontend.Response response) {
                return response.getListMetadataValues().getValueList();
              }
            });
  }

  /**
   * Copy download job implementation to a S3 location
   */
  public ListenableFuture<String> copyJobImpl(final String id, URI destination)
          throws ServiceClientException {
    Frontend.ImplGetRequest.Builder getReq =
            Frontend.ImplGetRequest.newBuilder()
                    .setDestination(destination.toString())
                    .setId(id);

    Frontend.Request.Builder req =
            Frontend.Request.newBuilder()
                    .setImplGet(getReq);

    return Futures.transform(
            post(req.build()),
            new Function<Frontend.Response, String>() {
              @Override
              public String apply(Frontend.Response response) {
                return id;
              }
            });
  }

  /**
   * Utility for the end-to-end posting of a request.
   */
  private ListenableFuture<Frontend.Response> post(Frontend.Request req)
          throws ServiceClientException {

    ServiceExchange<Frontend.Response> exchange = JsonServiceExchangeFactory
        .call(HttpMethod.POST, _serviceUri, req, Frontend.Response.newBuilder(), _options);
    
    ListenableFuture<Frontend.Response> pm = executeWithRetry(new Callable<ListenableFuture<Frontend.Response>>() {
      public ListenableFuture<Frontend.Response> call() throws ServiceClientException {
        return _client.send(exchange);
      }
    });

    return instrumentForErrorHandling(exchange, pm);
  }

  /**
   * Transforms future into a future that will throw ServiceException.
   */
  private static ListenableFuture<Frontend.Response> instrumentForErrorHandling(
          final ServiceExchange<Frontend.Response> e1,
          ListenableFuture<Frontend.Response> future) {
    return MoreFutures.transform(
            future,
            new FutureTransform<Frontend.Response, Frontend.Response>() {
              @Override
              public ListenableFuture<Frontend.Response> transform(Frontend.Response e2) {
                return Futures.immediateFuture(e2);
              }

              @Override
              public ListenableFuture<Frontend.Response> create(Throwable t) {
                if (t instanceof ServiceClientException) {
                  ServiceClientException exc = (ServiceClientException) t;

                  // If we manage to get a response from the exchange, then
                  // throw that as a nice exception.
                  Frontend.Response response = null;
                  try {
                    if (e1.getResult().isDone()) {
                      response = e1.getResult().get();
                    }
                  } catch (Exception e) {
                    System.err.println(e);
                    // on purpose ignore all exceptions. We'll just rethrow
                    // the original exception.
                  }

                  if (response != null)
                    return Futures.immediateFailedFuture(
                      new SteveClientException(exc.getMessage(), exc.getHttpStatus().orElse(-1), response));
                }

                return Futures.immediateFailedFuture(t);
              }
            });
  }

  protected <V> ListenableFuture<V> executeWithRetry(Callable<ListenableFuture<V>> callable) {
    int initialDelay = 300;
    int maxDelay = 20 * 1000;

    ThrowableRetryPolicy trp = new ExpBackoffRetryPolicy(
      initialDelay, maxDelay, _retryCount, TimeUnit.MILLISECONDS) {
      @Override
      public boolean retryOnThrowable(Throwable t) {
        return ! (t instanceof SteveClientException || t instanceof ServiceClientException);
      }
    };

    Callable<ListenableFuture<V>> rt = new ThrowableRetriableTask(callable, _scheduler, trp);
    ListenableFuture<V> f;
    try  {
      f = rt.call();
    } catch (Exception e) {
      f = Futures.immediateFailedFuture(e);
    }

    return f;
  }

  protected int _retryCount = 5;
}
