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
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.client.ProtobufServiceClient;
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
import com.logicblox.steve.protocol.Database.Request;
import com.logicblox.steve.protocol.Database.Response;
import com.logicblox.steve.protocol.Database.SetJobImplRequest;
import com.logicblox.steve.protocol.Database.SetResultRequest;
import com.logicblox.steve.protocol.Database.ListPlatformsRequest;
import com.logicblox.steve.protocol.Database.ListQueuesRequest;
import com.logicblox.steve.protocol.Database.ListMetadataKeysRequest;
import com.logicblox.steve.protocol.Database.ListMetadataValuesRequest;


/**
 * An implementation of Database backed by a LogicBlox workspace. This implementation does not cache
 * anything, so all method invocations hit the database.
 */
public class LBDatabase implements Database {

  final Logger _logger = SystemDLogger.getLogger("LBDatabase");
  final String _dbServicesPrefix;

  final LBDatabaseBatcher _getUserBatcher;
  final LBDatabaseBatcher _createJobBatcher;
  final LBDatabaseBatcher _addStatusBatcher;
  final LBDatabaseBatcher _setResultBatcher;
  final LBDatabaseBatcher _getJobBatcher;
  final LBDatabaseBatcher _setJobImplBatcher;
  final LBDatabaseBatcher _getJobImplBatcher;
  final LBDatabaseBatcher _getQueuesBatcher;
  final LBDatabaseBatcher _getPlatformsBatcher;
  final LBDatabaseBatcher _getMetadataKeysBatcher;
  final LBDatabaseBatcher _getMetadataValuesBatcher;

  public LBDatabase() {
    this("http://localhost:8080/db");
  }

  public LBDatabase(String dbServicesPrefix) {
    _dbServicesPrefix = dbServicesPrefix;

    _createJobBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/createJob").createProtobufClient(), false);
    _createJobBatcher.start();

    _addStatusBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/addStatus").createProtobufClient(), false);
    _addStatusBatcher.start();

    _setResultBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/setResult").createProtobufClient(), false);
    _setResultBatcher.start();

    _setJobImplBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/setJobImpl").createProtobufClient(), false);
    _setJobImplBatcher.start();

    _getJobBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getJob").createProtobufClient(), true);
    _getJobBatcher.start();

    _getUserBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getUser").createProtobufClient(), true);
    _getUserBatcher.start();

    _getJobImplBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getJobImpl").createProtobufClient(), true);
    _getJobImplBatcher.start();

    _getQueuesBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getQueues").createProtobufClient(), true);
    _getQueuesBatcher.start();

    _getPlatformsBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getPlatforms").createProtobufClient(), true);
    _getPlatformsBatcher.start();

    _getMetadataKeysBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getMetadataKeys").createProtobufClient(), true);
    _getMetadataKeysBatcher.start();

    _getMetadataValuesBatcher = new LBDatabaseBatcher(ServiceConnector.create(_dbServicesPrefix+"/getMetadataValues").createProtobufClient(), true);
    _getMetadataValuesBatcher.start();
  }
  
  /**
   * Allow the database implementation to cleanup resources.
   */
  public void shutdown() {
    _getUserBatcher.shutdown();
    _createJobBatcher.shutdown();
    _addStatusBatcher.shutdown();
    _setResultBatcher.shutdown();
    _getJobBatcher.shutdown();
    _setJobImplBatcher.shutdown();
    _getJobImplBatcher.shutdown();
    _getQueuesBatcher.shutdown();
    _getPlatformsBatcher.shutdown();
    _getMetadataKeysBatcher.shutdown();
    _getMetadataValuesBatcher.shutdown();
  }

  @Override
  public ListenableFuture<User> getUser(String userId) {
    // create database request
    final Request request = Request.newBuilder()
            .setGetUser(GetUserRequest.newBuilder()
                            .setUserId(userId)
            ).build();    
    
    return Futures.transform(_getUserBatcher.addRequest(request),
            new Function<Response, User>() {
              public User apply(Response response) {

                checkError(response);
                final com.logicblox.steve.protocol.Database.User user = response.getUser();
                return new User(user.getId(), user.getAccountId(), user.getPublicKey());
              }
            });
  }

  @Override
  public ListenableFuture<Account> getAccount(String userId) {
    return Futures.transform(getUser(userId),
            new Function<User, Account>() {
              public Account apply(User u) {
                return new Account(u.getAccountId());
              }
            });
  }


  //
  // JOBs
  //

  @Override
  public ListenableFuture<Job> createJob(
          final String userId,
          final String clientId,
          final String jobImplId,
          final Collection<Data> inputs,
          final String output,
          final String output_encryption_key,
          final Map<String, String> metadata) {

    CreateJobRequest.Builder builder = CreateJobRequest.newBuilder()
                            .setJobId(UUID.randomUUID().toString())
                            .setUserId(userId)
                            .setClientId(clientId)
                            .setImplId(jobImplId)
                            .setOutputPrefix(output)
                            .addAllInput(Conversions.convertToDatabaseFiles(inputs))
                            .addAllMetadata(Conversions.convertToDatabaseParams(metadata));

    if(output_encryption_key != null && ! "".equals(output_encryption_key)) {
      builder.setOutputEncryptionKey(output_encryption_key);
    }

    // create database request
    final Request request = Request.newBuilder().setCreateJob(builder).build();

    // submit and process the response
    return Futures.transform(_createJobBatcher.addRequest(request),
            new Function<Response, Job>() {
              public Job apply(Response response) {

                checkError(response);
                return convertFromDatabase(response.getJob());
              }
            });
  }


  @Override
  public ListenableFuture<String> addStatus(final String jobId, final Status status) {
    // create database request
    final Request request = Request.newBuilder()
            .setAddStatus(AddStatusRequest.newBuilder()
                            .setJobId(jobId)
                            .setStatus(Conversions.convertToDatabaseStatus(status))
            ).build();
    
    // submit and process the response
    return Futures.transform(_addStatusBatcher.addRequest(request),
            new Function<Response, String>() {
              public String apply(Response response) {

                checkError(response);
                return jobId;
              }
            });
  }


  @Override
  public ListenableFuture<String> setResult(final String jobId, final List<Data> output) {
    // create database request
    final Request request = Request.newBuilder()
            .setSetResult(SetResultRequest.newBuilder()
                            .setJobId(jobId)
                            .addAllOutput(Conversions.convertToDatabaseFiles(output))
            ).build();
    
    // submit and process the response
    return Futures.transform(_setResultBatcher.addRequest(request),
            new Function<Response, String>() {
              public String apply(Response response) {

                checkError(response);
                return response.getJob().getId();
              }
            });
  }

  private Job convertFromDatabase(com.logicblox.steve.protocol.Database.Job job) {
    return new Job(job.getId(),
                   job.getUserId(),
                   job.getAccountId(),
                   job.getClientId(),
                   job.getOutputPrefix(),
                   job.getOutputEncryptionKey(),
                   job.getImplId(),
                   Conversions.convertFromDatabaseParams(job.getMetadataList()),
                   Conversions.convertFromDatabaseFiles(job.getInputList()),
                   job.getImplArchive(),
                   job.hasCpuUsage() ? job.getCpuUsage(): 0,
                   job.hasMaxMemory() ? job.getMaxMemory() : 0,
                   job.hasMaxDiskUsage() ? job.getMaxDiskUsage() : 0,
                   Conversions.convertFromDatabaseFiles(job.getOutputList()),
                   Conversions.convertFromDatabaseStatus(job.getStatusList())
                 );
  }

  @Override
  public ListenableFuture<Job> getJob(final String jobId) {
    // create database request
    final Request request = Request.newBuilder()
            .setGetJob(GetJobRequest.newBuilder()
                            .setJobId(jobId)
                            .setGetStatus(true)
            ).build();

    // submit and process the response
    return Futures.transform(_getJobBatcher.addRequest(request),
            new Function<Response, Job>() {
              public Job apply(Response response) {

                checkError(response);
                return convertFromDatabase(response.getJob());
              }
            });    
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
    final Request request = Request.newBuilder()
            .setSetImpl(SetJobImplRequest.newBuilder()
                            .setUserId(userId)
                            .setImplId(implId)
                            .setFile(Conversions.convertToDatabaseFile(file))
                            .addAllMetadata(Conversions.convertToDatabaseParams(metadata))
            ).build();

    // submit and process the response
    return Futures.transform(_setJobImplBatcher.addRequest(request),
            new Function<Response, String>() {
              public String apply(Response response) {

                checkError(response);
                return implId;
              }
            });
  }

  @Override
  public ListenableFuture<JobImpl> getJobImpl(final String userId, final String id) {
    // create database request
    final Request request = Request.newBuilder()
            .setGetImpl(GetJobImplRequest.newBuilder()
                            .setUserId(userId)
                            .setImplId(id)
            ).build();

    // submit and process the response
    return Futures.transform(_getJobImplBatcher.addRequest(request),
            new Function<Response, JobImpl>() {
              public JobImpl apply(Response response) {

                checkError(response);
                final com.logicblox.steve.protocol.Database.JobImpl impl = response.getImpl(0);

                return new JobImpl(
                        impl.getId(),
                        impl.getAccountId(),
                        Conversions.convertFromDatabaseFile(impl.getFile()),
                        Conversions.convertFromDatabaseParams(impl.getMetadataList()));
              }
            });
  }

  @Override
  public ListenableFuture<Iterable<JobImpl>> getJobImpl(final String userId) {
    // create database request
    final Request request = Request.newBuilder()
            .setGetImpl(GetJobImplRequest.newBuilder()
                            .setUserId(userId)
            ).build();
    
    // submit and process the response
    return Futures.transform(_getJobImplBatcher.addRequest(request),
            new Function<Response, Iterable<JobImpl>>() {
              public Iterable<JobImpl> apply(Response response) {

                checkError(response);
                final Builder<JobImpl> builder = ImmutableList.builder();
                for (final com.logicblox.steve.protocol.Database.JobImpl impl : response.getImplList())
                  builder.add(new JobImpl(
                                  impl.getId(),
                                  impl.getAccountId(),
                                  Conversions.convertFromDatabaseFile(impl.getFile()),
                                  Conversions.convertFromDatabaseParams(impl.getMetadataList()))
                  );
                return builder.build();
              }
            });
  }

  @Override
  public ListenableFuture<Iterable<String>> getQueues() {
    final Request request = Request.newBuilder().setListQueues(ListQueuesRequest.newBuilder().build()).build();
    return Futures.transform(_getQueuesBatcher.addRequest(request),
            new Function<Response, Iterable<String>>() {
               public Iterable<String> apply(Response response) {
                   return response.getQueueList();
               }
           });
  }

  @Override
  public ListenableFuture<Iterable<String>> getPlatforms() {
    final Request request = Request.newBuilder().setListPlatforms(ListPlatformsRequest.newBuilder().build()).build();
    return Futures.transform(_getPlatformsBatcher.addRequest(request),
            new Function<Response, Iterable<String>>() {
               public Iterable<String> apply(Response response) {
                   return response.getPlatformList();
               }
           });
  }

  @Override
  public ListenableFuture<Iterable<String>> getMetadataKeys(String user) {
    final Request request = Request.newBuilder().setListMetadataKeys(ListMetadataKeysRequest.newBuilder().setUserId(user).build()).build();
    return Futures.transform(_getMetadataKeysBatcher.addRequest(request),
            new Function<Response, Iterable<String>>() {
               public Iterable<String> apply(Response response) {
                   return response.getMetadataKeyList();
               }
           });
  }

  @Override
  public ListenableFuture<Iterable<String>> getMetadataValues(String user, String key) {
    final Request request = Request.newBuilder().setListMetadataValues(ListMetadataValuesRequest.newBuilder().setUserId(user).setKey(key).build()).build();
    return Futures.transform(_getMetadataValuesBatcher.addRequest(request),
            new Function<Response, Iterable<String>>() {
               public Iterable<String> apply(Response response) {
                   return response.getMetadataValueList();
               }
           });
  }

  //
  // HELPERS
  //

  /**
   * Check if this response has an error, and throws an appropriate exception if so.
   *
   * @param response
   * @return the response, if it does not contain errors.
   */
  private Response checkError(Response response) {
    if (response.getErrorList().size() > 0) {
      throw new ServiceException(
              new SimpleErrorCode(
                      response.getErrorList().get(0).getCode(),
                      400,
                      response.getErrorList().get(0).getMessage()));
    }
    return response;
  }
  
  
}
