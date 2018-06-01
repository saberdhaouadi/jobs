package com.logicblox.steve.keyserver;

import java.io.File;
import java.io.FileFilter;
import java.io.IOException;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.filefilter.WildcardFileFilter;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.InvalidRequestException;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.ProtoBufHandler;
import com.logicblox.bloxweb.config.Section;
import com.logicblox.bloxweb.service.ServiceConfig;
import com.logicblox.steve.protocol.Keys;
import com.logicblox.web.server.http.HttpRequest;
import com.logicblox.web.server.http.HttpResponse;

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
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          ProtoBufExchange exchange)
          throws IOException, InvalidProtocolBufferException, InvalidRequestException {
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
