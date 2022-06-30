package com.logicblox.steve;

import com.amazonaws.services.s3.model.ObjectMetadata;
import com.google.common.base.Charsets;
import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.io.Files;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.logicblox.bloxweb.*;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.config.Section;
import com.logicblox.bloxweb.service.ServiceConfig;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.concurrent.MoreFutures;
import com.logicblox.cloudstore.S3Client;
import com.logicblox.cloudstore.StoreFile;
import com.logicblox.cloudstore.CopyOptions;
import com.logicblox.cloudstore.CopyOptionsBuilder;
import com.logicblox.cloudstore.Utils;
import com.logicblox.cloudstore.ExistsOptions;
import com.logicblox.cloudstore.DownloadOptions;
import com.logicblox.cloudstore.Metadata;
import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSClients;
import com.logicblox.sqs.SQSException;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.common.Status;
import com.logicblox.steve.common.Status.StatusBuilder;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.db.Job;
import com.logicblox.steve.db.JobImpl;
import com.logicblox.steve.db.LBDatabase;
import com.logicblox.steve.frontend.JobQueueClient;
import com.logicblox.steve.frontend.StatusQueueClient;
import com.logicblox.steve.protocol.Frontend;
import com.logicblox.web.common.http.HttpException;
import com.logicblox.web.common.http.HttpStatus;
import com.logicblox.web.server.http.HttpRequest;
import com.logicblox.web.server.http.HttpResponse;
import com.timgroup.statsd.NonBlockingStatsDClient;
import com.timgroup.statsd.StatsDClient;


import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;

public class SteveHandler extends ProtoBufHandler {
  private static final long MAX_IMPL_SIZE = 70;
  private static final long MAX_LOG_SIZE = 50;

  private StatsDClient _statsd;
  private Database _db;
  private Map<String, JobQueueClient> _jobQueues = new HashMap<String, JobQueueClient>();
  private S3Client _s3client;
  private File _tmpDir;
  private String _jobImplPrefix;
  private String _jobLogPrefix;
  private String _defaultQueue;
  private String _dataDir;
  private String _maintenanceFile;

  public SteveHandler() {
    super("Steve");
  }

  @Override
  public void init(Section handlerConfig, ServiceConfig service) {
    super.init(handlerConfig, service);

    _statsd = new NonBlockingStatsDClient("lb.steve", "127.0.0.1", 8125);

    String dbPrefix = handlerConfig.getStringError("database_prefix");
    _db = new LBDatabase(dbPrefix);

    try
    {
      _s3client = S3Utils.createS3Client(handlerConfig);
    }
    catch(java.net.MalformedURLException ex)
    {
      throw new HandlerValidationException(ex);
    }

    _tmpDir = handlerConfig.getFileError("tmpdir");

    _maintenanceFile = handlerConfig.getStringError("logdir")+"/maintenance";
    _dataDir = handlerConfig.getStringError("logdir")+"/status";

    Section jobImplConfig = handlerConfig.getParent().getSection("job-implementations");
    _jobImplPrefix = jobImplConfig.getStringError("prefix");

    Section jobLogConfig = handlerConfig.getParent().getSection("job-logs");
    _jobLogPrefix = jobLogConfig.getStringError("prefix");

    try {
      SQSClients sqsClients = new SQSClients();

      for (String sectionName : handlerConfig.getParent().getSectionNames()) {
        if (sectionName.startsWith("job-queue:")) {
          Section jobQueueConfig = handlerConfig.getParent().getSection(sectionName);
          SQSClient jobClient = sqsClients.getSQSClient(jobQueueConfig);
          SQSQueueHandle jobQueue = getQueueFromConfig(jobClient, jobQueueConfig);
          JobQueueClient client = new JobQueueClient(jobClient, jobQueue);
          String key = sectionName.substring(sectionName.indexOf(':') + 1);

          _jobQueues.put(key, client);
          if (jobQueueConfig.getBool("default", false)) {
            _jobQueues.put(null, client);
            _defaultQueue = key;
          }
        }
      }

      // If there is only a single job-queue section, and it was not
      // marked as the default, then automatically make it the default.
      if (_jobQueues.size() == 1) {
        JobQueueClient single = null;
        for (Map.Entry<String, JobQueueClient> entry : _jobQueues.entrySet())
          single = entry.getValue();
        _jobQueues.put(null, single);
      }

      if (!_jobQueues.containsKey(null))
        throw new UsageException("No default job queue is configured");

      Section statusQueueConfig = handlerConfig.getParent().getSection("status-queue");
      SQSClient statusClient = sqsClients.getSQSClient(statusQueueConfig);

      SQSQueueHandle statusQueue = getQueueFromConfig(statusClient, statusQueueConfig);
      StatusQueueClient status = new StatusQueueClient(statusClient, statusQueue, _db, _dataDir);
      status.start();
    } catch (SQSException exc) {
      throw new HandlerValidationException(exc);
    }
  }

  private SQSQueueHandle getQueueFromConfig(SQSClient sqs, Section config) throws SQSException {
    boolean create = config.getBool("create", false);
    if (config.contains("sqs_queue_url")) {
      return sqs.getQueue(URI.create(config.getStringError("sqs_queue_url")), create);
    } else if (config.contains("sqs_queue_name")) {
      return sqs.getQueue(config.getStringError("sqs_queue_name"), create);
    } else
      throw new HandlerValidationException(
              "sqs_queue_url or sqs_queue_url is needed for section '" + config.getSectionName() + "'", null);
  }

  @Override
  protected Frontend.Request.Builder getRequestBuilder() {
    return Frontend.Request.newBuilder();
  }

  @Override
  protected Frontend.Response.Builder getResponseBuilder() {
    return Frontend.Response.newBuilder();
  }

  @Override
  public FileDescriptorSet getRequestProtocolDescriptor() {
    return createFileDescriptorSet(Frontend.getDescriptor());
  }

  @Override
  public FileDescriptorSet getResponseProtocolDescriptor() {
    // same protocol as request
    return getRequestProtocolDescriptor();
  }

  @Override
  public void description(StringBuilder out) {
    out.append("<li>Steve jobs handler</li>");
  }

  /**
   * Extract the username from the HTTP request.
   *
   * @param request
   * @return
   */
  public String getUser(HttpRequest request) {
    // TODO - this is somewhat costly, maybe we should cache.
    // this assumes the user is authenticated with a signature based realm.
    final Map<String, String> params = new HashMap<String, String>();
    HandlerUtils.populateHeaderMap(request, params);
    final String[] auth = params.get("authorization").split(":", 3);
    return auth[0];
  }

  private boolean inMaintenance() {
    return new File(_maintenanceFile).exists();
  }

  @Override
  protected ListenableFuture<ProtoBufExchange> handle(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          ProtoBufExchange exchange)
          throws IOException, InvalidProtocolBufferException, InvalidRequestException {
    Frontend.Request request = (Frontend.Request) exchange.getRequestMessage();

    ListenableFuture<Frontend.Response> resp;

    if (inMaintenance()) {
      resp = Futures.immediateFailedFuture(new ServiceException(new SimpleErrorCode("MAINTENANCE", 503, "System is in maintenance mode, please try again in a few minutes")));
      return MoreFutures.transferResponse(resp, exchange);
    }

    if (request.hasCreate()) {
      resp = handleCreate(httpRequest, httpResponse, request.getCreate());
    } else if (request.hasState()) {
      resp = handleState(httpRequest, httpResponse, request.getState());
    } else if (request.hasResult()) {
      resp = handleResult(httpRequest, httpResponse, request.getResult());
    } else if (request.hasCancel()) {
      resp = Futures.immediateFailedFuture(
              new HttpException(HttpStatus.BAD_REQUEST_400, "Not yet implemented"));
    } else if (request.hasLog()) {
      resp = handleLog(httpRequest, httpResponse, request.getLog());
    } else if (request.hasLbLogs()) {
      resp = handleLBLogs(httpRequest, httpResponse, request.getLbLogs());
    } else if (request.hasImplAdd()) {
      resp = handleImplAdd(httpRequest, httpResponse, request.getImplAdd());
    } else if (request.hasImplGet()) {
      resp = handleImplGet(httpRequest, httpResponse, request.getImplGet());
    } else if (request.hasImplList()) {
      resp = handleImplList(httpRequest, httpResponse, request.getImplList());
    } else if (request.hasListPlatforms()) {
      resp = handleListPlatforms(httpRequest, httpResponse, request.getListPlatforms());
    } else if (request.hasListQueues()) {
      resp = handleListQueues(httpRequest, httpResponse, request.getListQueues());
    } else if (request.hasListMetadataKeys()) {
      resp = handleListMetadataKeys(httpRequest, httpResponse, request.getListMetadataKeys());
    } else if (request.hasListMetadataValues()) {
      resp = handleListMetadataValues(httpRequest, httpResponse, request.getListMetadataValues());
    } else {
      resp = Futures.immediateFailedFuture(
              new ServiceException(
                      new SimpleErrorCode(
                              "REQUEST_INVALID", HttpStatus.BAD_REQUEST_400, "Request union requires one request")));
    }

    return MoreFutures.transferResponse(resp, exchange);
  }

  private ListenableFuture<Frontend.Response> handleCreate(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.JobCreateRequest req) {
    _statsd.incrementCounter("create_job");

    final String user = getUser(httpRequest);
    Map<String, String> tags = Conversions.createMap(req.getMetadataList());
    tags.put("date", Conversions.getCurrentISO8601());

    final String jobQueueId = tags.get("job-queue");
    if (jobQueueId != null && !_jobQueues.containsKey(jobQueueId)) {
      return Futures.immediateFailedFuture(
              new ServiceException(
                      new SimpleErrorCode(
                              "NO_SUCH_JOB_QUEUE", HttpStatus.BAD_REQUEST_400, "Job queue '" + jobQueueId + "' does not exist")));
    }

    if(jobQueueId == null && _defaultQueue != null) {
      tags.put("job-queue", _defaultQueue);
    }

    ListenableFuture<Job> job =
            _db.createJob(
                    user,
                    req.getClientId(),
                    req.getJobImpl(),
                    Conversions.convertFrontendFileToData(req.getInputList()),
                    req.getOutput(),
                    req.hasOutputEncryptionKey() ? req.getOutputEncryptionKey() : null,
                    tags);

    job = Futures.transformAsync(
       job, 
       new AsyncFunction<Job, Job>() {
          public ListenableFuture<Job> apply(Job j) {
            return _jobQueues.get(jobQueueId).submit(j);
          }
        },
        MoreExecutors.directExecutor());

    // Once the job is submitted, construct a response to return the client
    return Futures.transform(
            job,
            new Function<Job, Frontend.Response>() {
              public Frontend.Response apply(Job job) {
                Frontend.Response.Builder response = Frontend.Response.newBuilder();
                response.setCreate(
                        Frontend.JobCreateResponse.newBuilder()
                                .setJobId(job.id));

                return response.build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleState(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          final Frontend.StateRequest req) {
    _statsd.incrementCounter("set_state");

    ListenableFuture<Job> job = _db.getJob(req.getId());

    return Futures.transform(
            job,
            new Function<Job, Frontend.Response>() {
              public Frontend.Response apply(Job job) {
                Frontend.State.Builder b = Frontend.State.newBuilder();

                if (job.isSucceeded())
                  b.setState("SUCCEEDED");
                else if (job.isFailed())
                  b.setState("FAILED");
                else
                  // TODO wait until we have proper state handling
                  b.setState("UNKNOWN");

                if (req.hasDetail() && req.getDetail()) {
                  for (Status status : job.getStatus()) {
                    Frontend.Status.Builder protoStatus =
                            Frontend.Status.newBuilder()
                                    .setTimestamp(status.timestamp)
                                    .setMachine(status.machine)
                                    .setStatusCode(status.event.toString());

                    if (status.hasMessage())
                      protoStatus.setMessage(status.message);

                    b.addStatus(protoStatus);
                  }
                }

                Frontend.Response.Builder response =
                        Frontend.Response.newBuilder()
                                .setState(
                                        Frontend.StateResponse.newBuilder()
                                                .setState(b));

                return response.build();
              }
            },
            MoreExecutors.directExecutor());
  }

  /**
   * Validate that this job is completed and that it succeeded.
   *
   * @param job
   */
  private void validateJobDone(final Job job) {
    // TODO add user account and only return job when it exists in this account.
    // TODO throw authorization exception if the user is not allowed to access the job
    if (!job.isSucceeded()) {
      if (job.isFailed()) {
        throw new ServiceException(
                new SimpleErrorCode("JOB_FAILED", 400, "Job '" + job.id + "' failed and has no output"));
      } else {
        throw new ServiceException(
                new SimpleErrorCode("JOB_INCOMPLETE", 400, "Job '" + job.id + "' has not completed and has no output"));
      }
    }
  }

  private ListenableFuture<Frontend.Response> handleResult(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          final Frontend.JobResultRequest req) {
    _statsd.incrementCounter("get_result");
    ListenableFuture<Job> job = _db.getJob(req.getJobId());

    return Futures.transformAsync(
            job,
            new AsyncFunction<Job, Frontend.Response>() {
              public ListenableFuture<Frontend.Response> apply(Job job) {

                validateJobDone(job);

                Frontend.JobResultResponse.Builder b = Frontend.JobResultResponse.newBuilder();

                for (Data d : job.getOutputData()) {
                  b.addOutput(Conversions.convertDataToFrontendFile(d));
                }

                /**
                 * Only set this once all clients have updated to the new client,
                 * with these fields in the protocol.
                 */
                //if( job.cpuUsage != 0 && job.maxMemory != 0) {
                //  b.setCpuUsage(job.cpuUsage);
                //  b.setMaxMemory(job.maxMemory);
                //  b.setMaxDiskUsage(job.maxDiskUsage);
                //}

                Frontend.Response.Builder response = Frontend.Response.newBuilder();
                response.setResult(b);
                return Futures.immediateFuture(response.build());
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleLog(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          final Frontend.JobLogRequest req)
          throws IOException {
    _statsd.incrementCounter("get_log");

    // TODO - if we decide to allow this operation only on jobs that have succeeded (which is
    // what this call to getResult seemed to do), then we need a call to _db.getJob followed by
    // a validateJobDone.
    //ListenableFuture<Job> job = _db.getResult(req.getJobId());

    final File tmpFile = File.createTempFile("joblog", null, _tmpDir);
    URI tmpUrl;
    try {
      tmpUrl = new URI(_jobLogPrefix + "/" + req.getJobId() + "/log");
    } catch (URISyntaxException exc) {
      throw new ServiceException(
              new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax"));
    }
    final URI inputUrl = tmpUrl;

    ExistsOptions existsOptions =
          _s3client.getOptionsBuilderFactory()
              .newExistsOptionsBuilder()
              .setBucketName(com.logicblox.cloudstore.Utils.getBucketName(inputUrl))
              .setObjectKey(com.logicblox.cloudstore.Utils.getObjectKey(inputUrl))
              .createOptions();

    ListenableFuture<Metadata> metadata = _s3client.exists(existsOptions);

    // Check the S3 metadata, and if we're okay, then download the
    // log from S3 to a temporary file
    ListenableFuture<StoreFile> inputFile = Futures.transformAsync(
            metadata,
            new AsyncFunction<Metadata, StoreFile>() {
              public ListenableFuture<StoreFile> apply(Metadata m) throws IOException {
                if (m == null)
                  throw new ServiceException(
                          new SimpleErrorCode("FILE_NOT_FOUND", 400, "Log does not exist"));

                if (m.getContentLength() > MAX_LOG_SIZE * 1048576L)
                  throw new ServiceException(
                          new SimpleErrorCode("MAX_SIZE_EXCEEDED", 400, "Log is too big"));

                DownloadOptions downloadOptions = _s3client.getOptionsBuilderFactory()
                  .newDownloadOptionsBuilder()
                  .setFile(tmpFile)
                  .setBucketName(com.logicblox.cloudstore.Utils.getBucketName(inputUrl))
                  .setObjectKey(com.logicblox.cloudstore.Utils.getObjectKey(inputUrl))
                  .setOverwrite(true)
                  .createOptions();
                return _s3client.download(downloadOptions);
              }
            },
            MoreExecutors.directExecutor());

    ListenableFuture<String> log = Futures.transformAsync(
            inputFile,
            new AsyncFunction<StoreFile, String>() {
              public ListenableFuture<String> apply(StoreFile logfile) throws IOException {
                List<String> lines = Files.readLines(logfile.getLocalFile(), Charsets.UTF_8);
                Joiner joiner = Joiner.on("\n");
                String log = joiner.join(lines);
                return Futures.immediateFuture(log);
              }
            },
            MoreExecutors.directExecutor());

    ListenableFuture<Frontend.Response> futureRes = Futures.transformAsync(
            log,
            new AsyncFunction<String, Frontend.Response>() {
              public ListenableFuture<Frontend.Response> apply(String log) {
                Frontend.JobLogResponse.Builder b = Frontend.JobLogResponse.newBuilder();
                b.setLog(log);

                Frontend.Response.Builder response = Frontend.Response.newBuilder();
                response.setLog(b);
                return Futures.immediateFuture(response.build());
              }
            },
            MoreExecutors.directExecutor());

    return MoreFutures.compose(futureRes, new Runnable() {
      @Override
      public void run() throws SecurityException {
        tmpFile.delete();
      }
    });
  }

  private ListenableFuture<Frontend.Response> handleLBLogs(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.JobLBLogsRequest req) throws IOException {
    _statsd.incrementCounter("get_lb_logs");

    final String user = getUser(httpRequest);

    URI tmpUrl;
    try {
      tmpUrl = Utils.getURI(req.getDestination());
    } catch (URISyntaxException exc) {
      throw new ServiceException(
              new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax"));
    }

    final URI dest = tmpUrl;
    URI logs;
    try {
      logs = new URI(_jobLogPrefix + "/" + req.getId() + "/lb-logs.tgz");
    }
    catch(URISyntaxException e) {
      return Futures.immediateFailedFuture(new ServiceException(new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax")));
    }
    ListenableFuture<Metadata> md = _s3client.exists(
        _s3client.getOptionsBuilderFactory()
            .newExistsOptionsBuilder()
            .setBucketName(
                com.logicblox.cloudstore.Utils.getBucketName(logs))
            .setObjectKey(com.logicblox.cloudstore.Utils.getObjectKey(logs))
            .createOptions());
    ListenableFuture<StoreFile> s3File = Futures.transformAsync(md,
           new AsyncFunction<Metadata, StoreFile>() {
              public ListenableFuture<StoreFile> apply(Metadata m) throws IOException {
                  if (m == null)
                    throw new ServiceException(
                            new SimpleErrorCode("FILE_NOT_FOUND", 400, "Log does not exist"));

                  CopyOptions options =
                      _s3client.getOptionsBuilderFactory()
                          .newCopyOptionsBuilder()
                          .setSourceBucketName(Utils.getBucketName(logs))
                          .setSourceObjectKey(Utils.getObjectKey(logs))
                          .setDestinationBucketName(Utils.getBucketName(dest))
                          .setDestinationObjectKey(Utils.getObjectKey(dest))
                          .setCannedAcl("bucket-owner-full-control")
                          .createOptions();
                  return _s3client.copy(options);
              }
           },
           MoreExecutors.directExecutor());

    return Futures.transform(
            s3File,
            new Function<StoreFile, Frontend.Response>() {
              public Frontend.Response apply(StoreFile loc) {
                Frontend.File file = Conversions.convertDataToFrontendFile(Conversions.convertStoreFileToData(loc));
                Frontend.ImplGetResponse.Builder resp = Frontend.ImplGetResponse.newBuilder().setFile(file);

                return
                        Frontend.Response.newBuilder()
                                .setImplGet(resp)
                                .build();
              }
            },
            MoreExecutors.directExecutor());

  }

  /**
   * Handle a request to add a new job implementation.
   */
  private ListenableFuture<Frontend.Response> handleImplAdd(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          final Frontend.ImplAddRequest req)
          throws IOException {
    _statsd.incrementCounter("upload_impl");

    final File tmpFile = File.createTempFile("jobimpl", null, _tmpDir);
    final String id = UUID.randomUUID().toString();
    final String user = getUser(httpRequest);

    URI tmpUrl;
    try {
      tmpUrl = new URI(req.getImplementation().getUrl());
    } catch (URISyntaxException exc) {
      throw new ServiceException(
              new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax"));
    }

    final URI inputUrl = tmpUrl;

    ListenableFuture<Metadata> metadata = _s3client.exists(
        _s3client.getOptionsBuilderFactory()
            .newExistsOptionsBuilder()
            .setBucketName(
                com.logicblox.cloudstore.Utils.getBucketName(inputUrl))
            .setObjectKey(com.logicblox.cloudstore.Utils.getObjectKey(inputUrl))
            .createOptions());

    ListenableFuture<StoreFile> inputFile =
            Futures.transformAsync(metadata, new AsyncFunction<Metadata, StoreFile>() {
              @Override
              public ListenableFuture<StoreFile> apply(Metadata m) throws Exception {
                if (m == null)
                  throw new ServiceException(
                          new SimpleErrorCode("FILE_NOT_FOUND", 400, "S3 file does not exist"));

                if (req.getImplementation().hasHash())
                  if (!S3Utils.verifyHash(m, req.getImplementation().getHash()))
                    throw new ServiceException(
                            new SimpleErrorCode("INVALID_HASH", 400,
                                    "Specified hash does not correspond to actual hash"));

                if (m.getContentLength() > MAX_IMPL_SIZE * 1048576L)
                  throw new ServiceException(
                          new SimpleErrorCode("MAX_SIZE_EXCEEDED", 400, "Implementation is too big"));

                // TODO check the account of the encryption key used.
                return _s3client.download(
                    _s3client.getOptionsBuilderFactory()
                        .newDownloadOptionsBuilder()
                        .setFile(tmpFile)
                        .setBucketName(
                            com.logicblox.cloudstore.Utils.getBucketName(
                                inputUrl))
                        .setObjectKey(
                            com.logicblox.cloudstore.Utils.getObjectKey(
                                inputUrl))
                        .setOverwrite(true)
                        .createOptions());
              }
            },
            MoreExecutors.directExecutor());

    inputFile = Futures.catchingAsync(
       inputFile,
       Throwable.class,
       new AsyncFunction<Throwable, StoreFile>()
       {
         public ListenableFuture<StoreFile> apply(Throwable t)
         {
           if (t instanceof ServiceException) {
             return Futures.immediateFailedFuture(t);
           } else {
             return Futures.immediateFailedFuture(new ServiceException(
                new SimpleErrorCode("ERROR_FETCHING", 400, "Could not fetch job implementation")));
           }
         }
       },
       MoreExecutors.directExecutor());

    // Upload file to S3
    ListenableFuture<StoreFile> newFile = Futures.transformAsync(
            inputFile,
            new AsyncFunction<StoreFile, StoreFile>() {
              public ListenableFuture<StoreFile> apply(StoreFile input) throws IOException {
                URI jobUri = URI.create(_jobImplPrefix + "/" + id + ".tar.gz");

                // TODO verify etag again
                return _s3client.upload(
                    _s3client.getOptionsBuilderFactory()
                        .newUploadOptionsBuilder()
                        .setFile(input.getLocalFile())
                        .setBucketName(
                            com.logicblox.cloudstore.Utils.getBucketName(
                                jobUri))
                        .setObjectKey(
                            com.logicblox.cloudstore.Utils.getObjectKey(jobUri))
                        .createOptions());
              }
            },
            MoreExecutors.directExecutor());

    final Map<String, String> tags = Conversions.createMap(req.getMetadataList());
    tags.put("date", Conversions.getCurrentISO8601());

    ListenableFuture<String> jobImplId = Futures.transformAsync(
            newFile,
            new AsyncFunction<StoreFile, String>() {
              public ListenableFuture<String> apply(StoreFile input) throws IOException {
                return _db.setJobImpl(
                        user,
                        req.getId(),
                        Conversions.convertStoreFileToData(input),
                        tags);
              }
            },
            MoreExecutors.directExecutor());

    ListenableFuture<Job> job = Futures.transformAsync(
            jobImplId,
            new AsyncFunction<String, Job>() {
              public ListenableFuture<Job> apply(String impl) {
                return _db.createJob(
                        user,
                        req.getClientId(),
                        "steve:internal:process-jobimpl",
                        Conversions.convertFrontendFileToData(
                                Collections.singletonList(req.getImplementation())),
                        "",
                        null,
                        tags);
              }
            },
            MoreExecutors.directExecutor());

    ListenableFuture<String> jobId = Futures.transformAsync(
            job,
            new AsyncFunction<Job, String>() {
              public ListenableFuture<String> apply(Job j) {
                final StatusBuilder status = new StatusBuilder();
                status.event = Status.Event.SUCCEEDED;
                status.machine = "frontend";
                status.timestamp = System.currentTimeMillis();

                return _db.addStatus(j.id, status.build());
              }
            },
            MoreExecutors.directExecutor());

    ListenableFuture<Frontend.Response> futureRes = Futures.transform(
            jobId,
            new Function<String, Frontend.Response>() {
              public Frontend.Response apply(String id) {
                return
                        Frontend.Response.newBuilder()
                                .setImplAdd(
                                        Frontend.ImplAddResponse.newBuilder()
                                                .setId(id))
                                .build();
              }
            },
            MoreExecutors.directExecutor());

    return MoreFutures.compose(futureRes, new Runnable() {
      @Override
      public void run() throws SecurityException {
        tmpFile.delete();
      }
    });
  }

  private ListenableFuture<Frontend.Response> handleImplGet(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ImplGetRequest req) throws IOException {
    _statsd.incrementCounter("get_impl");

    final String user = getUser(httpRequest);

    URI tmpUrl;
    try {
      tmpUrl = Utils.getURI(req.getDestination());
    } catch (URISyntaxException exc) {
      throw new ServiceException(
              new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax"));
    }

    final URI dest = tmpUrl;

    ListenableFuture<JobImpl> impl = _db.getJobImpl(user, req.getId());
    ListenableFuture<StoreFile> s3File = Futures.transformAsync(impl,
           new AsyncFunction<JobImpl, StoreFile>() {
              public ListenableFuture<StoreFile> apply(JobImpl impl) throws IOException {
                URI archive;
                try {
                  archive = Utils.getURI(impl.archive.getLocation());
                }
                catch(URISyntaxException e) {
                  return Futures.immediateFailedFuture(new ServiceException(new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax")));
                }
                CopyOptions options =
                    _s3client.getOptionsBuilderFactory()
                        .newCopyOptionsBuilder()
                        .setSourceBucketName(Utils.getBucketName(archive))
                        .setSourceObjectKey(Utils.getObjectKey(archive))
                        .setDestinationBucketName(Utils.getBucketName(dest))
                        .setDestinationObjectKey(Utils.getObjectKey(dest))
                        .setCannedAcl("bucket-owner-full-control")
                        .createOptions();
                return _s3client.copy(options);
              }
           },
           MoreExecutors.directExecutor());

    return Futures.transform(
            s3File,
            new Function<StoreFile, Frontend.Response>() {
              public Frontend.Response apply(StoreFile loc) {
                Frontend.File file = Conversions.convertDataToFrontendFile(Conversions.convertStoreFileToData(loc));
                Frontend.ImplGetResponse.Builder resp = Frontend.ImplGetResponse.newBuilder().setFile(file);

                return
                        Frontend.Response.newBuilder()
                                .setImplGet(resp)
                                .build();
              }
            },
            MoreExecutors.directExecutor());

  }

  /**
   * Handle a request to list job implementations.
   */
  private ListenableFuture<Frontend.Response> handleImplList(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ImplListRequest req) {
    _statsd.incrementCounter("list_impl");

    final String user = getUser(httpRequest);
    return Futures.transform(
            _db.getJobImpl(user),
            new Function<Iterable<JobImpl>, Frontend.Response>() {
              public Frontend.Response apply(Iterable<JobImpl> impls) {
                Frontend.ImplListResponse.Builder resp = Frontend.ImplListResponse.newBuilder();
                for (JobImpl impl : impls) {
                  if (!impl.id.startsWith("steve:internal:"))
                    resp.addJobImpl(createImplInfo(impl));
                }

                return
                        Frontend.Response.newBuilder()
                                .setImplList(resp)
                                .build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleListPlatforms(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ListPlatformsRequest req) {
    _statsd.incrementCounter("list_platforms");

    return Futures.transform(
            _db.getPlatforms(),
            new Function<Iterable<String>, Frontend.Response>() {
              public Frontend.Response apply(Iterable<String> platforms) {
                Frontend.ListPlatformsResponse.Builder resp = Frontend.ListPlatformsResponse.newBuilder();
                for (String platform: platforms) {
                  resp.addPlatform(platform);
                }
                return Frontend.Response.newBuilder().setListPlatforms(resp).build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleListQueues(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ListQueuesRequest req) {
    _statsd.incrementCounter("list_queues");

    return Futures.transform(
            _db.getQueues(),
            new Function<Iterable<String>, Frontend.Response>() {
              public Frontend.Response apply(Iterable<String> queues) {
                Frontend.ListQueuesResponse.Builder resp = Frontend.ListQueuesResponse.newBuilder();
                for (String queue: queues) {
                  resp.addQueue(queue);
                }
                return Frontend.Response.newBuilder().setListQueues(resp).build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleListMetadataKeys(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ListMetadataKeysRequest req) {
    _statsd.incrementCounter("list_metadata_keys");

    final String user = getUser(httpRequest);
    return Futures.transform(
            _db.getMetadataKeys(user),
            new Function<Iterable<String>, Frontend.Response>() {
              public Frontend.Response apply(Iterable<String> keys) {
                Frontend.ListMetadataKeysResponse.Builder resp = Frontend.ListMetadataKeysResponse.newBuilder();
                for (String key: keys) {
                  resp.addKey(key);
                }
                return Frontend.Response.newBuilder().setListMetadataKeys(resp).build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private ListenableFuture<Frontend.Response> handleListMetadataValues(
          HttpRequest httpRequest,
          HttpResponse httpResponse,
          Frontend.ListMetadataValuesRequest req) {
    _statsd.incrementCounter("list_metadata_values");

    final String user = getUser(httpRequest);
    return Futures.transform(
            _db.getMetadataValues(user, req.getKey()),
            new Function<Iterable<String>, Frontend.Response>() {
              public Frontend.Response apply(Iterable<String> values) {
                Frontend.ListMetadataValuesResponse.Builder resp = Frontend.ListMetadataValuesResponse.newBuilder();
                for (String value: values) {
                  resp.addValue(value);
                }
                return Frontend.Response.newBuilder().setListMetadataValues(resp).build();
              }
            },
            MoreExecutors.directExecutor());
  }

  private static Frontend.JobImplInfo createImplInfo(JobImpl impl) {
    Frontend.JobImplInfo.Builder info = Frontend.JobImplInfo.newBuilder();
    info.setId(impl.id);
    for (Map.Entry<String, String> entry : impl.metadata.entrySet()) {
      info.addMetadata(Conversions.createFrontendParam(entry.getKey(), entry.getValue()));
    }
    return info.build();
  }
}
