package com.logicblox.steve.worker;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.Encoding;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.client.Transports;
import com.logicblox.bloxweb.client.ProtobufServiceClient;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;

import com.logicblox.s3lib.ThrowableRetryPolicy;
import com.logicblox.s3lib.ThrowableRetriableTask;
import com.logicblox.s3lib.ExpBackoffRetryPolicy;

import java.util.HashMap;
import java.util.Map;

import com.logicblox.steve.protocol.Keys;
import java.lang.Exception;

public class SteveKeyServerHelper {
  private ProtobufServiceClient _client;
  private ListeningScheduledExecutorService _scheduler;

  public SteveKeyServerHelper(String uri) {
     _client = ServiceConnector.create().setTransport(Transports.tcp()).setURI(uri).setEncoding(Encoding.JSON).createProtobufClient();
     _scheduler = MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(1));
  }

  public Map<String, String> getKeys(String account) throws Exception {
    Keys.GetKeysRequest req = Keys.GetKeysRequest.newBuilder().setAccount(account).build();
    ProtoBufExchange exchange = new ProtoBufExchange(req, Keys.GetKeysResponse.newBuilder());

    ListenableFuture<Keys.GetKeysResponse> pm = executeWithRetry(new Callable<ListenableFuture<Keys.GetKeysResponse>>() {
      public ListenableFuture<Keys.GetKeysResponse> call() throws InvalidProtocolBufferException, ServiceClientException {
        return Futures.immediateFuture( (Keys.GetKeysResponse) _client.postMessage(exchange).result().getResponseMessage());
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
