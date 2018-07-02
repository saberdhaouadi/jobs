package com.logicblox.steve.common;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.s3.model.ObjectMetadata;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;

import com.logicblox.cloudstore.DirectoryKeyProvider;
import com.logicblox.cloudstore.KeyProvider;
import com.logicblox.cloudstore.S3Client;
import com.logicblox.cloudstore.S3ClientBuilder;
import com.logicblox.cloudstore.Utils;

import com.logicblox.bloxweb.config.ConfigMap;

import java.io.File;
import java.util.concurrent.Executors;

public class S3Utils {
  private static ListeningExecutorService getHttpExecutor(ConfigMap c) {
    // TODO make concurrent connections configurable
    int maxConcurrentConnections = 10;
    return MoreExecutors.listeningDecorator(
            Executors.newFixedThreadPool(maxConcurrentConnections));
  }

  private static ListeningScheduledExecutorService getInternalExecutor(ConfigMap c) {
    return MoreExecutors.listeningDecorator(
            Executors.newScheduledThreadPool(25));
  }

  protected static KeyProvider getKeyProvider(ConfigMap config) {
    // TODO make key directory configurable
    File dir = new File(Utils.getDefaultKeyDirectory());
    return new DirectoryKeyProvider(dir);
  }

  /**
   * Create an S3Client from a configuration
   */
  public static S3Client createS3Client(ConfigMap config) {
    // TODO make retry count configurable
    int retryCount = 7;

    S3Client result = new S3ClientBuilder()
      .setApiExecutor(getHttpExecutor(config))
      .setInternalExecutor(getInternalExecutor(config))
      .setKeyProvider(getKeyProvider(config))
      .createS3Client();

    result.setRetryCount(retryCount);
    if(config != null && config.isSome("s3_endpoint")) {
      result.setEndpoint(config.getStringError("s3_endpoint"));
    }
    return result;
  }

  /**
   * The hash needs to have the syntax "hash-type:hash-value", where
   * the supported hash-type is currently only 'etag'.
   */
  public static boolean verifyHash(ObjectMetadata metadata, String hash) {
    if (metadata == null)
      throw new IllegalArgumentException("metadata must not be null");
    if (hash == null)
      throw new IllegalArgumentException("hash must not be null");

    String etag;
    if (hash.startsWith("etag:")) {
      etag = hash.substring("etag:".length());
    } else
      throw new IllegalArgumentException("Unsupported hash '" + hash + "'");

    return metadata.getETag().equals(etag);
  }
}
