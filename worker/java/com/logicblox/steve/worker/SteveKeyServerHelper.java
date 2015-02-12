package com.logicblox.steve.worker;

import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.Encoding;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.client.Transports;
import com.logicblox.bloxweb.client.ProtobufServiceClient;

import java.util.HashMap;
import java.util.Map;

import com.logicblox.steve.protocol.Keys;
import java.lang.Exception;

public class SteveKeyServerHelper {
  private ProtobufServiceClient _client;

  public SteveKeyServerHelper(String uri) {
     _client = ServiceConnector.create().setTransport(Transports.tcp()).setURI(uri).setEncoding(Encoding.JSON).createProtobufClient();
  }

  public Map<String, String> getKeys(String account) throws Exception {
    Keys.GetKeysRequest req = Keys.GetKeysRequest.newBuilder().setAccount(account).build();
    ProtoBufExchange exchange = new ProtoBufExchange(req, Keys.GetKeysResponse.newBuilder());
    Keys.GetKeysResponse resp = (Keys.GetKeysResponse) _client.postMessage(exchange).result().getResponseMessage();

    HashMap result = new HashMap<String, String>();
    for(Keys.Key key : resp.getKeyList()) {
      result.put(key.getName(), key.getContents());
    }
    return result;
  }
}
