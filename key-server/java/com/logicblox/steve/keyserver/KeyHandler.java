package com.logicblox.steve.keyserver;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;

import java.util.concurrent.Callable;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureFallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.HandlerUtils;
import com.logicblox.bloxweb.HandlerValidationException;
import com.logicblox.bloxweb.InvalidRequestException;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.ProtoBufHandler;
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.config.Section;
import com.logicblox.bloxweb.service.ServiceConfig;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.concurrent.MoreFutures;
import com.logicblox.cloudstore.S3Client;
import com.logicblox.cloudstore.StoreFile;
import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSClients;
import com.logicblox.sqs.SQSException;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.common.Status;
import com.logicblox.steve.common.Status.StatusBuilder;
import com.logicblox.steve.protocol.Keys;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.FileUtils;
import java.io.FileFilter;

public class KeyHandler extends ProtoBufHandler {
  public File keyDir;

  public KeyHandler() {
    super("key");
  }

  @Override
  public void init(Section handlerConfig, ServiceConfig service) {
    super.init(handlerConfig, service);
    keyDir = new File(handlerConfig.getStringError("key_dir"));
  }

  @Override
  protected Keys.GetKeysRequest.Builder getRequestBuilder() {
    return Keys.GetKeysRequest.newBuilder();
  }

  @Override
  protected Keys.GetKeysResponse.Builder getResponseBuilder() {
    return Keys.GetKeysResponse.newBuilder();
  }

  @Override
  public FileDescriptorSet getRequestProtocolDescriptor() {
    return createFileDescriptorSet(Keys.getDescriptor());
  }

  @Override
  public FileDescriptorSet getResponseProtocolDescriptor() {
    // same protocol as request
    return getRequestProtocolDescriptor();
  }

  @Override
  public void description(StringBuilder out) {
    out.append("<li>Steve key handler</li>");
  }

  @Override
  protected ListenableFuture<ProtoBufExchange> handle(
          HttpServletRequest httpRequest,
          HttpServletResponse httpResponse,
          ProtoBufExchange exchange)
          throws ServletException, IOException, InvalidProtocolBufferException, InvalidRequestException {
    Keys.GetKeysRequest request = (Keys.GetKeysRequest) exchange.getRequestMessage();

    File accountDir = new File(keyDir, request.getAccount());
    Keys.GetKeysResponse.Builder builder = Keys.GetKeysResponse.newBuilder();

    if(accountDir.exists()) {
      FileFilter fileFilter = new WildcardFileFilter("*.pem");
      File[] files = accountDir.listFiles(fileFilter);
      for(File k : files) {
          Keys.Key.Builder keyBuilder = Keys.Key.newBuilder();
          keyBuilder.setName(FilenameUtils.getBaseName(k.getName()));
          try {
            keyBuilder.setContents(FileUtils.readFileToString(k));
          } catch(IOException e) {
            // not sure yet what to do on failure to read
          }
          builder.addKey(keyBuilder.build());
      }
    }

    Keys.GetKeysResponse resp = builder.build();
    exchange.setResponseMessage(resp);
    return Futures.immediateFuture(exchange);
  }

}
