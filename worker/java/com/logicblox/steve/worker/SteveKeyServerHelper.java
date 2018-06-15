package com.logicblox.steve.worker;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.cloudstore.ExpBackoffRetryPolicy;
import com.logicblox.cloudstore.ThrowableRetriableTask;
import com.logicblox.cloudstore.ThrowableRetryPolicy;
import com.logicblox.steve.protocol.Keys;
import com.logicblox.web.client.service.ServiceClient;
import com.logicblox.web.client.service.ServiceClientException;

public class SteveKeyServerHelper {
  private ServiceClient _client = new ServiceClient(); 
  private ListeningScheduledExecutorService _scheduler;
  private String _uri;
  
  public SteveKeyServerHelper(String uri) {
     _uri = uri;
     _scheduler = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  public Map<String, String> getKeys(String account) throws Exception {
    Keys.GetKeysRequest req = Keys.GetKeysRequest.newBuilder().setAccount(account).build();

    ListenableFuture<Keys.GetKeysResponse> pm = executeWithRetry(new Callable<ListenableFuture<Keys.GetKeysResponse>>() {
      public ListenableFuture<Keys.GetKeysResponse> call() throws InvalidProtocolBufferException, ServiceClientException {
        return _client.postJSON(_uri, req, Keys.GetKeysResponse.newBuilder());
      }
    });

    Keys.GetKeysResponse resp = pm.get();

    HashMap result = new HashMap<String, String>();
    for(Keys.Key key : resp.getKeyList()) {
      result.put(key.getName(), key.getContents());
    }
    return result;
  }

  protected <V> ListenableFuture<V> executeWithRetry(Callable<ListenableFuture<V>> callable) {
    int initialDelay = 300;
    int maxDelay = 20 * 1000;
    int retryCount = 10;

    ThrowableRetryPolicy trp = new ExpBackoffRetryPolicy(
      initialDelay, maxDelay, retryCount, TimeUnit.MILLISECONDS) {
      @Override
      public boolean retryOnThrowable(Throwable t) {
        return true;
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

}
