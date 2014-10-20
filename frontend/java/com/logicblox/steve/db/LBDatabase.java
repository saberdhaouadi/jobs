package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDLogger;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Status;
import com.logicblox.steve.protocol.Database.AddStatusRequest;
import com.logicblox.steve.protocol.Database.CreateJobRequest;
import com.logicblox.steve.protocol.Database.GetJobImplRequest;
import com.logicblox.steve.protocol.Database.GetJobRequest;
import com.logicblox.steve.protocol.Database.GetUserRequest;
import com.logicblox.steve.protocol.Database.RequestEnvelope;
import com.logicblox.steve.protocol.Database.Response;
import com.logicblox.steve.protocol.Database.ResponseEnvelope;
import com.logicblox.steve.protocol.Database.SetJobImplRequest;
import com.logicblox.steve.protocol.Database.SetResultRequest;

/**
 * An implementation of Database backed by a LogicBlox workspace. This implementation does not cache
 * anything, so all method invocations hit the database.
 */
public class LBDatabase implements Database {

  final Logger _logger = SystemDLogger.getLogger("LBDatabase");
  final String _dbServicesPrefix;
  
  public LBDatabase() {
    this("http://localhost:8080/db/");
  }
  
  public LBDatabase(String dbServicesPrefix) {
    _dbServicesPrefix = dbServicesPrefix;
  }
  
  // TODO - make getUser async.
  @Override
  public User getUser(String userId) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addGetUser(GetUserRequest.newBuilder()
            .setUserId(userId)
         ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "get_user"),
          new Function<ProtoBufExchange, User>() {
            public User apply(ProtoBufExchange exchange) {
              
              final Response response = checkError(envelope(exchange).getResponse(0));
              final com.logicblox.steve.protocol.Database.User user = response.getUser();
              return new User(user.getId(), user.getAccountId(), user.getPublicKey());
            }
          }).get();
      
    } catch (ServiceClientException | InterruptedException | ExecutionException e) {
      throw new RuntimeException(e);
    }
  }

  // TODO - make getAccount async.
  @Override
  public Account getAccount(String userId) {
    return new Account(getUser(userId).getAccountId());
  }

  
  //
  // JOBs
  //  
  
  @Override
  public ListenableFuture<String> createJob(
      final String userId,
      final String clientId,
      final String jobImplId,
      final Collection<Data> inputs,
      final String output,
      final Map<String, String> metadata) {
        
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addCreateJob(CreateJobRequest.newBuilder()
            .setJobId(UUID.randomUUID().toString())
            .setUserId(userId)
            .setClientId(clientId)
            .setImplId(jobImplId)
            .setOutputPrefix(output)
            .addAllInput(Conversions.convertToDatabaseFiles(inputs))
            .addAllMetadata(Conversions.convertToDatabaseParams(metadata))
         ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "create_job"),
          new Function<ProtoBufExchange, String>() {
            public String apply(ProtoBufExchange exchange) {

                final Response response = checkError(envelope(exchange).getResponse(0));
                return response.getJobId();
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }
  

  @Override
  public ListenableFuture<String> addStatus(final String jobId, final Status status) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addAddStatus(AddStatusRequest.newBuilder()
            .setJobId(jobId)
            .setStatus(Conversions.convertToDatabaseStatus(status))
        ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "add_status"),
          new Function<ProtoBufExchange, String>() {
            public String apply(ProtoBufExchange exchange) {

              checkError(envelope(exchange).getResponse(0));
              return jobId;
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }
  
  
  @Override
  public ListenableFuture<String> setResult(final String jobId, final List<Data> output) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addSetResult(SetResultRequest.newBuilder()
            .setJobId(jobId)
            .addAllOutput(Conversions.convertToDatabaseFiles(output))
        ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "set_result"),
          new Function<ProtoBufExchange, String>() {
            public String apply(ProtoBufExchange exchange) {

              final Response response = checkError(envelope(exchange).getResponse(0));
              return response.getJob().getId();
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }
  

  @Override
  public ListenableFuture<Job> getJob(final String jobId) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addGetJob(GetJobRequest.newBuilder()
            .setJobId(jobId)
            .setGetInput(true)
            .setGetMetadata(true)
            .setGetOutput(true)
            .setGetStatus(true)
         ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "get_job"),
          new Function<ProtoBufExchange, Job>() {
            public Job apply(ProtoBufExchange exchange) {

                final Response response = checkError(envelope(exchange).getResponse(0));

                final com.logicblox.steve.protocol.Database.Job job = response.getJob();
                return new Job(job.getId(),
                    job.getClientId(),
                    job.getOutputPrefix(),
                    job.getImplId(),
                    Conversions.convertFromDatabaseParams(job.getMetadataList()),
                    Conversions.convertFromDatabaseFiles(job.getInputList()),
                    job.getImplArchive(),
                    Conversions.convertFromDatabaseFiles(job.getOutputList()),
                    Conversions.convertFromDatabaseStatus(job.getStatusList()));
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

 


  //
  // JOB IMPLEMENTATIONs
  //
  
  @Override
  public ListenableFuture<String> setJobImpl(
      final String userId,
      final String implId,
      final Data file,
      final Map<String, String> metadata) {
    
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addSetImpl(SetJobImplRequest.newBuilder()
            .setUserId(userId)
            .setImplId(implId)
            .setFile(Conversions.convertToDatabaseFile(file))
            .addAllMetadata(Conversions.convertToDatabaseParams(metadata))
        ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "set_impl"),
          new Function<ProtoBufExchange, String>() {
            public String apply(ProtoBufExchange exchange) {

              checkError(envelope(exchange).getResponse(0));
              return implId;
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<JobImpl> getJobImpl(final String userId, final String id) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addGetImpl(GetJobImplRequest.newBuilder()
            .setUserId(userId)
            .setImplId(id)
         ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "get_impl"),
          new Function<ProtoBufExchange, JobImpl>() {
            public JobImpl apply(ProtoBufExchange exchange) {

                final Response response = checkError(envelope(exchange).getResponse(0));

                final com.logicblox.steve.protocol.Database.JobImpl impl = response.getImpl(0);

                return new JobImpl(
                    impl.getId(),
                    impl.getAccountId(),
                    Conversions.convertFromDatabaseFile(impl.getFile()),
                    Conversions.convertFromDatabaseParams(impl.getMetadataList()));
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  @Override
  public ListenableFuture<Iterable<JobImpl>> getJobImpl(final String userId) {
    // create database request
    final RequestEnvelope request = RequestEnvelope.newBuilder()
        .addGetImpl(GetJobImplRequest.newBuilder()
            .setUserId(userId)
         ).build();
    
    try {
      // submit and process the response
      return Futures.transform(submit(request, "get_impl"),
          new Function<ProtoBufExchange, Iterable<JobImpl>>() {
            public Iterable<JobImpl> apply(ProtoBufExchange exchange) {

                final ResponseEnvelope env = envelope(exchange);
                final Builder<JobImpl> builder = ImmutableList.builder();

                if (env.getResponseCount()==0)
                  return builder.build();

                final Response response = checkError(env.getResponse(0));
                for (final com.logicblox.steve.protocol.Database.JobImpl impl: response.getImplList())
                  builder.add(new JobImpl(
                    impl.getId(),
                    impl.getAccountId(),
                    Conversions.convertFromDatabaseFile(impl.getFile()),
                    Conversions.convertFromDatabaseParams(impl.getMetadataList()))
                  );
                return builder.build();
            }
          });
      
    } catch (ServiceClientException e) {
      return Futures.immediateFailedFuture(e);
    }
  }

  //
  // HELPERS
  //
  
  

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
  
  /**
   * Helper to cast the response message in the exchange into a ResponseEnvelope. 
   * 
   * @param exchange
   * @return
   */
  private ResponseEnvelope envelope(ProtoBufExchange exchange) {
    try {
      return (ResponseEnvelope) exchange.getResponseMessage();
    } catch (InvalidProtocolBufferException e) {
      throw new RuntimeException(e);
    }
  }
  
  /**
   * Check if this response has an error, and throws an appropriate exception if so.
   * 
   * @param response
   * @return the response, if it does not contain errors.
   */
  private Response checkError(Response response) {
    if (response.hasError()) {
      throw new ServiceException(
          new SimpleErrorCode(
              response.getError().getCode(),
              400,
              response.getError().getMessage()));
    }
    return response;
  }
}
