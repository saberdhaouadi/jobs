package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.google.common.base.Function;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.authentication.Credentials.CredentialResponse;
import com.logicblox.bloxweb.authentication.CredentialsServiceClient;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDLogger;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.protocol.Database.CreateJobRequest;
import com.logicblox.steve.protocol.Database.RequestEnvelope;
import com.logicblox.steve.protocol.Database.Response;
import com.logicblox.steve.protocol.Database.ResponseEnvelope;

/**
 * An implementation of Database backed by a LogicBlox workspace. This implementation does not cache
 * anything, so all method invocations hit the database.
 */
public class LBDatabase implements Database {

  final Logger _logger = SystemDLogger.getLogger("LBDatabase");
  final CredentialsServiceClient _credentialsClient;
  final ServiceConnector _connector;
  final String _dbServicesPrefix;
  
  public LBDatabase() {
    this("http://localhost:8080/db/", "http://localhost:8080/admin/credentials");
  }
  
  public LBDatabase(String dbServicesPrefix, String credentialsServiceURL) {
    _credentialsClient = new CredentialsServiceClient("http://localhost:8080/admin/credentials", _logger);
    _connector = ServiceConnector.create();
    _dbServicesPrefix = dbServicesPrefix;
  }
  
  // TODO - make getUser async.
  @Override
  public User getUser(String userId) {
    try {
      final CredentialResponse response = _credentialsClient.getCredentials(userId).get();
      return new User(userId, "logicblox.com", response.getGet(0).getPublicKey());
    } catch (Exception e) {
      e.printStackTrace();
      return null;
    }
  }

  // TODO - make getAccount async.
  @Override
  public Account getAccount(String userId) {
    return new Account(getUser(userId).getAccountId());
  }

  // TODO - in order to batch requests, we need one queue for each service.
  
  /**
   * Submit this request to this service.
   * 
   * @param request the request envelope to submit.
   * @param service the name of the service to target.
   * @return a future that will contain the resulting ProtoBufExchange when the submission is over.
   * @throws ServiceClientException if there are any problems executing the submission.
   */
  private ListenableFuture<ProtoBufExchange> submit(
      RequestEnvelope request, 
      String service)
      throws ServiceClientException {
    
    final ProtoBufExchange exchange = new ProtoBufExchange(request, ResponseEnvelope.newBuilder());
    return ServiceConnector
        .create(_dbServicesPrefix + service)
        .createProtobufClient()
        .postMessage(exchange);    
  }
  
  @Override
  public ListenableFuture<Job> createJob(
      final String userId,
      final String clientId,
      final String jobImpl,
      final Collection<Data> inputs,
      final String output,
      final Map<String, String> metadata) {
        
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addCreateJob(CreateJobRequest.newBuilder()
            .setJobId(UUID.randomUUID().toString())
            .setClientId(clientId)
            .setImplId(jobImpl)
            .setOutputPrefix(output)
            .addAllInput(Conversions.convertDataToDatabaseFiles(inputs))
            .addAllMetadata(Conversions.convertDataToDatabaseParams(metadata))
         ).build();
    
    try {
      
      return Futures.transform(submit(request, "create_job"),
          new Function<ProtoBufExchange, Job>() {
            public Job apply(ProtoBufExchange exchange) {
              try {
                final Response response = 
                    ((ResponseEnvelope) exchange.getResponseMessage()).getResponse(0);
                if (response.hasError()) {
                  throw new ServiceException(
                      new SimpleErrorCode(
                          response.getError().getCode(),
                          400,
                          response.getError().getMessage()));
                }
                // TODO - perhaps it's better to make Job have only the job impl id, not the object.
                // then, when needed (as in JobQueueClient), we need to ask the database for impl
                // information
                final com.logicblox.steve.protocol.Database.Job job = response.getJob();
                return new Job(job.getId(), clientId, output, new JobImpl(jobImpl), metadata, inputs);
                
              } catch (Exception e) {
                // TODO 
                throw new RuntimeException(e);
              }
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<JobImpl> setJobImpl(String userId,
      String implId,
      Data file,
      Map<String, String> metadata) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<JobImpl> getJobImpl(String userId, String id) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<Iterable<JobImpl>> getJobImpl(String userId) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<Job> getState(String jobId, boolean detail) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<Job> getResult(String jobId) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<Job> addStatus(String jobId, Status status) {
    // TODO Auto-generated method stub
    return null;
  }

  @Override
  public ListenableFuture<Job> setResult(String jobId, List<Data> output) {
    // TODO Auto-generated method stub
    return null;
  }

}
