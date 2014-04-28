package com.logicblox.sqs;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.services.sqs.AmazonSQSClient;

import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.ListeningExecutorService;

import com.logicblox.aws.CredentialsConfig;
import com.logicblox.bloxweb.config.ConfigMap;

/**
 * Every AmazonSQS client has its own connection pool. This means
 * that we cannot be sloppy with creating AmazonSQS clients and have
 * to maximally reuse them. Sadly, the client has a configured
 * credentials and endpoint, so we need to cache them per credential
 * configuration and endpoint.
 */
public class SQSClients
{
  private final Map<String, SQSClient> _clients = new HashMap<String, SQSClient>();

  /**
   * Returns a shared AmazonSQSClient for a given AWS endpoint and
   * credential configuration.
   */
  public synchronized SQSClient getSQSClient(String endpoint, ConfigMap config)
  {
    CredentialsConfig creds = new CredentialsConfig(config);
    String key = endpoint + "@" + String.valueOf(creds);

    if(!_clients.containsKey(key))
    {
      AWSCredentialsProvider provider = creds.getAWSCredentialsProvider();
      AmazonSQSClient sqs = new AmazonSQSClient(provider);
      sqs.setEndpoint(endpoint);

      // TODO make configurable?
      ListeningExecutorService executor =
        MoreExecutors.listeningDecorator(Executors.newScheduledThreadPool(
            com.amazonaws.ClientConfiguration.DEFAULT_MAX_CONNECTIONS));

      SQSClient client = new SQSClient(endpoint, sqs, executor);
      _clients.put(key, client);
    }
    
    return _clients.get(key);
  }

  /**
   * Uses standard config option 'sqs_queue_url' and 'sqs_endpoint' to
   * determine endpoint.
   */
  public SQSClient getSQSClient(ConfigMap config)
  {
    String endpoint;
    if(config.contains("sqs_queue_url"))
    {
      URI uri = URI.create(config.getStringError("sqs_queue_url"));
      endpoint = SQSClient.getEndpoint(uri);
    }
    else
    {
      endpoint = config.getStringError("sqs_endpoint");
    }
    
    return getSQSClient(endpoint, config);
  }
}