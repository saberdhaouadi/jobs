package com.logicblox.steve.db;

import java.util.HashMap;
import java.util.Map;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDB;
import com.amazonaws.services.dynamodbv2.AmazonDynamoDBClient;
import com.amazonaws.services.dynamodbv2.model.AttributeValue;
import com.amazonaws.services.dynamodbv2.model.PutItemRequest;
import com.logicblox.bloxweb.client.ClientConfigUtils;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.common.logging.Logger;
import com.logicblox.steve.common.Status;

public class DynamoJobState implements JobState {
  private AmazonDynamoDB _db;
  private String _table;

  public DynamoJobState(Config config, Logger logger) {
    AWSCredentialsProvider provider = ClientConfigUtils.getAWSCredentials(config, "state", logger);
    _db = new AmazonDynamoDBClient(provider);
    _db.setEndpoint(config.getSection("state").getStringError("endpoint"));
    _table = config.getSection("state").getStringError("table");
  }

  public void initialize(String jobId) {
    Map<String, AttributeValue> item = new HashMap<String, AttributeValue>();
    item.put("Id", new AttributeValue(jobId));
    item.put("State", new AttributeValue(Status.State.INITIAL.toString()));
    PutItemRequest req = new PutItemRequest(_table, item);
    //PutItemResult result =
    _db.putItem(req);
  }
}
