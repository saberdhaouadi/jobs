package com.logicblox.steve;

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
import com.logicblox.bloxweb.HandlerValidationException;
import com.logicblox.bloxweb.InvalidRequestException;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.ProtoBufHandler;
import com.logicblox.bloxweb.SimpleErrorCode;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.config.Section;
import com.logicblox.bloxweb.service.ServiceConfig;
import com.logicblox.bloxweb.HandlerUtils;
import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.concurrent.MoreFutures;
import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;
import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSClients;
import com.logicblox.sqs.SQSException;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.db.DynamoJobState;
import com.logicblox.steve.db.FakeDatabase;
import com.logicblox.steve.db.Job;
import com.logicblox.steve.db.JobImpl;
import com.logicblox.steve.db.Status;
import com.logicblox.steve.db.Status.StatusBuilder;
import com.logicblox.steve.frontend.JobQueueClient;
import com.logicblox.steve.frontend.StatusQueueClient;
import com.logicblox.steve.protocol.Frontend;

public class SteveHandler extends ProtoBufHandler
{
  private static final long MAX_IMPL_SIZE = 50;
  private static final long MAX_LOG_SIZE = 50;

  private Database _db;
  private Map<String, JobQueueClient> _jobQueues = new HashMap<String, JobQueueClient>();
  private S3Client _s3client;
  private File _tmpDir;
  private String _jobImplPrefix;
  private String _jobLogPrefix;

  public SteveHandler()
  {
    super("Steve");
  }

  @Override
  public void init(Section handlerConfig, ServiceConfig service)
  {
    super.init(handlerConfig, service);
    _db = new FakeDatabase(new DynamoJobState(handlerConfig.getParent(), _logger));

    _s3client = S3Utils.createS3Client(handlerConfig);
    _tmpDir = handlerConfig.getFileError("tmpdir");

    Section jobImplConfig = handlerConfig.getParent().getSection("job-implementations");
    _jobImplPrefix = jobImplConfig.getStringError("prefix");

    Section jobLogConfig = handlerConfig.getParent().getSection("job-logs");
    _jobLogPrefix = jobLogConfig.getStringError("prefix");

    try
    {
      SQSClients sqsClients = new SQSClients();
      
      for(String sectionName : handlerConfig.getParent().getSectionNames())
      {
        if(sectionName.startsWith("job-queue:"))
        {
          Section jobQueueConfig = handlerConfig.getParent().getSection(sectionName);
          SQSClient jobClient = sqsClients.getSQSClient(jobQueueConfig);
          SQSQueueHandle jobQueue = getQueueFromConfig(jobClient, jobQueueConfig);
          JobQueueClient client = new JobQueueClient(jobClient, jobQueue);
          String key = sectionName.substring(sectionName.indexOf(':') + 1);
          
          _jobQueues.put(key, client);
          if(jobQueueConfig.getBool("default", false))
            _jobQueues.put(null, client);
        }
      }
      
      // If there is only a single job-queue section, and it was not
      // marked as the default, then automatically make it the default.
      if(_jobQueues.size() == 1)
      {
        JobQueueClient single = null;
        for(Map.Entry<String, JobQueueClient> entry : _jobQueues.entrySet())
          single = entry.getValue();
        _jobQueues.put(null, single);
      }

      if(!_jobQueues.containsKey(null))
        throw new UsageException("No default job queue is configured");
      
      Section statusQueueConfig = handlerConfig.getParent().getSection("status-queue");
      SQSClient statusClient = sqsClients.getSQSClient(statusQueueConfig);
      
      SQSQueueHandle statusQueue = getQueueFromConfig(statusClient, statusQueueConfig);
      StatusQueueClient status = new StatusQueueClient(statusClient, statusQueue, _db);
      status.start();
    }
    catch(SQSException exc)
    {
      throw new HandlerValidationException(exc);
    }
  }

  private SQSQueueHandle getQueueFromConfig(SQSClient sqs, Section config) throws SQSException
  {
    boolean create = config.getBool("create", false);
    if(config.contains("sqs_queue_url"))
    {
      return sqs.getQueue(URI.create(config.getStringError("sqs_queue_url")), create);
    }
    else if(config.contains("sqs_queue_name"))
    {
      return sqs.getQueue(config.getStringError("sqs_queue_name"), create);
    }
    else
      throw new HandlerValidationException(
        "sqs_queue_url or sqs_queue_url is needed for section '" + config.getSectionName() + "'", null);
  }

  @Override
  protected Frontend.Request.Builder getRequestBuilder()
  {
    return Frontend.Request.newBuilder();
  }

  @Override
  protected Frontend.Response.Builder getResponseBuilder()
  {
    return Frontend.Response.newBuilder();
  }

  @Override
  public FileDescriptorSet getRequestProtocolDescriptor()
  {
    return createFileDescriptorSet(Frontend.getDescriptor());
  }
  
  @Override
  public FileDescriptorSet getResponseProtocolDescriptor()
  {
    // same protocol as request
    return getRequestProtocolDescriptor();
  }

  @Override
  public void description(StringBuilder out)
  {
    out.append("<li>Steve jobs handler</li>");
  }

  public String getUser(HttpServletRequest request)
  {
    Map<String, String> params = new HashMap<String, String>();
    HandlerUtils.populateHeaderMap(request, params);
    String[] auth = params.get("authorization").split(":", 3);
    return auth[0];
  }

  @Override
  protected ListenableFuture<ProtoBufExchange> handle(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    ProtoBufExchange exchange)
  throws ServletException, IOException, InvalidProtocolBufferException, InvalidRequestException
  {
    Frontend.Request request = (Frontend.Request) exchange.getRequestMessage();

    ListenableFuture<Frontend.Response> resp;
    if(request.hasCreate())
    {
      resp = handleCreate(httpRequest, httpResponse, request.getCreate());
    }
    else if(request.hasState())
    {
      resp = handleState(httpRequest, httpResponse, request.getState());
    }
    else if(request.hasResult())
    {
      resp = handleResult(httpRequest, httpResponse, request.getResult());
    }
    else if(request.hasCancel())
    {
      resp = Futures.immediateFailedFuture(
        new HttpException(HttpStatus.BAD_REQUEST_400, "Not yet implemented"));
    }
    else if(request.hasLog())
    {
      resp = handleLog(httpRequest, httpResponse, request.getLog());
    }
    else if(request.hasImplAdd())
    {
      resp = handleImplAdd(httpRequest, httpResponse, request.getImplAdd());
    }
    else if(request.hasImplList())
    {
      resp = handleImplList(httpRequest, httpResponse, request.getImplList());
    }
    else
    {
      resp = Futures.immediateFailedFuture(
        new ServiceException(
          new SimpleErrorCode(
            "REQUEST_INVALID", HttpStatus.BAD_REQUEST_400, "Request union requires one request")));
    }
    
    return MoreFutures.transferResponse(resp, exchange);
  }

  private ListenableFuture<Frontend.Response> handleCreate(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    Frontend.JobCreateRequest req)
  {
    final String user = getUser(httpRequest);

    Map<String, String> tags = Conversions.createMap(req.getMetadataList());
    tags.put("date", Conversions.getCurrentISO8601());

    final String jobQueueId = tags.get("job-queue");
    if(jobQueueId != null && !_jobQueues.containsKey(jobQueueId))
    {
      return Futures.immediateFailedFuture(
        new ServiceException(
          new SimpleErrorCode(
            "NO_SUCH_JOB_QUEUE", HttpStatus.BAD_REQUEST_400, "Job queue '" + jobQueueId + "' does not exist")));
    }

    ListenableFuture<Job> job =
      _db.createJob(
        user,
        req.getClientId(),
        req.getJobImpl(),
        Conversions.convertFrontendFileToData(req.getInputList()),
        req.getOutput(),
        tags);

    // Once we have the job stored in the database, submit it to the queue
    job = Futures.transform(job, new AsyncFunction<Job, Job>()
    {
      public ListenableFuture<Job> apply(Job j)
      {
        return _jobQueues.get(jobQueueId).submit(j);
      }
    });

    // Once the job is submitted, construct a response to return the
    // client
    return Futures.transform(
      job,
      new Function<Job, Frontend.Response>()
      {
        public Frontend.Response apply(Job job)
        {
          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setCreate(
            Frontend.JobCreateResponse.newBuilder()
            .setJobId(job.id));
          
          return response.build();
        }
      });
  }

  private ListenableFuture<Frontend.Response> handleState(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    final Frontend.StateRequest req)
  {
    ListenableFuture<Job> job = _db.getState(req.getId(), req.hasDetail() && req.getDetail());

    return Futures.transform(
      job,
      new Function<Job, Frontend.Response>()
      {
        public Frontend.Response apply(Job job)
        {
          Frontend.State.Builder b = Frontend.State.newBuilder();

          if(job.isSucceeded())
            b.setState("SUCCEEDED");
          else if(job.isFailed())
            b.setState("FAILED");
          else
            // TODO wait until we have proper state handling
            b.setState("UNKNOWN");

          if(req.hasDetail() && req.getDetail())
          {
            for(Status status : job.getStatus())
            {
              Frontend.Status.Builder protoStatus =
                Frontend.Status.newBuilder()
                .setTimestamp(status.getTimestamp())
                .setMachine(status.getMachine())
                .setStatusCode(status.getEvent().toString());

              if(status.hasMessage())
                protoStatus.setMessage(status.getMessage());
              
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
      });
  }

  private ListenableFuture<Frontend.Response> handleResult(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    final Frontend.JobResultRequest req)
  {
    ListenableFuture<Job> job = _db.getResult(req.getJobId());
    
    return Futures.transform(
      job,
      new AsyncFunction<Job, Frontend.Response>()
      {
        public ListenableFuture<Frontend.Response> apply(Job job)
        {
          Frontend.JobResultResponse.Builder b = Frontend.JobResultResponse.newBuilder();

          for(Data d : job.getOutputData())
          {
            b.addOutput(Conversions.convertDataToFrontendFile(d));
          }

          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setResult(b);
          return Futures.immediateFuture(response.build());
        }
      });
  }

  private ListenableFuture<Frontend.Response> handleLog(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse,
    final Frontend.JobLogRequest req)
  throws IOException
  {
    //ListenableFuture<Job> job = 
        _db.getResult(req.getJobId());

    final File tmpFile = File.createTempFile("joblog", null, _tmpDir);
    URI tmpUrl;
    try
    {
      tmpUrl = new URI(_jobLogPrefix+"/"+req.getJobId()+"/log");
    }
    catch(URISyntaxException exc)
    {
      throw new ServiceException(
        new SimpleErrorCode("INVALID_URL_SYNTAX", 500, "Invalid URL syntax"));
    }
    final URI inputUrl = tmpUrl;

    ListenableFuture<ObjectMetadata> metadata = _s3client.exists(inputUrl);

    // Check the S3 metadata, and if we're okay, then download the
    // log from S3 to a temporary file
    ListenableFuture<S3File> inputFile = Futures.transform(
      metadata,
      new AsyncFunction<ObjectMetadata, S3File>()
      {
        public ListenableFuture<S3File> apply(ObjectMetadata m) throws IOException
        {
          if(m == null)
            throw new ServiceException(
              new SimpleErrorCode("FILE_NOT_FOUND", 400, "Log does not exist"));

          if(m.getContentLength() > MAX_LOG_SIZE * 1048576L)
            throw new ServiceException(
              new SimpleErrorCode("MAX_SIZE_EXCEEDED", 400, "Log is too big"));

          return _s3client.download(tmpFile, inputUrl);
        }
      });

    ListenableFuture<String> log = Futures.transform(
      inputFile,
      new AsyncFunction<S3File, String>()
      {
        public ListenableFuture<String> apply(S3File logfile) throws IOException
        {
          List<String> lines = Files.readLines(logfile.getLocalFile(), Charsets.UTF_8);
          Joiner joiner = Joiner.on("\n");
          String log = joiner.join(lines);
          return Futures.immediateFuture(log);
        }
      });

    ListenableFuture<Frontend.Response> futureRes = Futures.transform(
      log,
      new AsyncFunction<String, Frontend.Response>()
      {
        public ListenableFuture<Frontend.Response> apply(String log)
        {
          Frontend.JobLogResponse.Builder b = Frontend.JobLogResponse.newBuilder();
          b.setLog(log);

          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setLog(b);
          return Futures.immediateFuture(response.build());
        }
      });

    return MoreFutures.compose(futureRes, new Runnable()
      {
        @Override
        public void run() throws SecurityException
        {
          tmpFile.delete();
        }
      });
  }

  /**
   * Handle a request to add a new job implementation.
   */
  private ListenableFuture<Frontend.Response> handleImplAdd(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    final Frontend.ImplAddRequest req)
  throws IOException
  {
    final File tmpFile = File.createTempFile("jobimpl", null, _tmpDir);
    final String id = UUID.randomUUID().toString();
    final String user = getUser(httpRequest);

    URI tmpUrl;
    try
    {
      tmpUrl = new URI(req.getImplementation().getUrl());
    }
    catch(URISyntaxException exc)
    {
      throw new ServiceException(
        new SimpleErrorCode("INVALID_URL_SYNTAX", 400, "Invalid URL syntax"));
    }

    final URI inputUrl = tmpUrl;

    ListenableFuture<ObjectMetadata> metadata = _s3client.exists(inputUrl);

    ListenableFuture<S3File> inputFile =
      Futures.transform(metadata, new AsyncFunction<ObjectMetadata, S3File>()
        {
          @Override
          public ListenableFuture<S3File> apply(ObjectMetadata m) throws Exception
          {
            if(m == null)
              throw new ServiceException(
                new SimpleErrorCode("FILE_NOT_FOUND", 400, "S3 file does not exist"));

            if(req.getImplementation().hasHash())
              if(!S3Utils.verifyHash(m, req.getImplementation().getHash()))
                throw new ServiceException(
                  new SimpleErrorCode("INVALID_HASH", 400,
                    "Specified hash does not correspond to actual hash"));

            if(m.getContentLength() > MAX_IMPL_SIZE * 1048576L)
              throw new ServiceException(
                new SimpleErrorCode("MAX_SIZE_EXCEEDED", 400, "Implementation is too big"));

            // TODO check the account of the encryption key used.
            return _s3client.download(tmpFile, inputUrl);
          }
        });

    inputFile = Futures.withFallback(inputFile, new FutureFallback<S3File>()
      {
        @Override
        public ListenableFuture<S3File> create(Throwable t)
        {
          if(t instanceof ServiceException)
          {
            return Futures.immediateFailedFuture(t);
          }
          else
          {
            return Futures.immediateFailedFuture(new ServiceException(
              new SimpleErrorCode("ERROR_FETCHING", 500, "Could not fetch job implementation")));
          }
        }
      });

    // Upload file to S3
    ListenableFuture<S3File> newFile = Futures.transform(
      inputFile,
      new AsyncFunction<S3File, S3File>()
      {
        public ListenableFuture<S3File> apply(S3File input) throws IOException
        {
          URI jobUri = URI.create(_jobImplPrefix + "/" + id + ".tar.gz");

          // TODO verify etag again
          return _s3client.upload(input.getLocalFile(), jobUri);
        }
      });

    final Map<String, String> tags = Conversions.createMap(req.getMetadataList());
    tags.put("date", Conversions.getCurrentISO8601());

    ListenableFuture<JobImpl> jobImpl = Futures.transform(
      newFile,
      new AsyncFunction<S3File, JobImpl>()
      {
        public ListenableFuture<JobImpl> apply(S3File input) throws IOException
        {
          // TODO use actual authenticated user
          return _db.setJobImpl(
            user,
            req.getId(),
            Conversions.convertS3FileToData(input),
            tags);
        }
      });

    ListenableFuture<Job> fakeJob = Futures.transform(
      jobImpl,
      new AsyncFunction<JobImpl, Job>()
      {
        public ListenableFuture<Job> apply(JobImpl impl)
        {
          // TODO use actual authenticated user
          return _db.createJob(
            user,
            req.getClientId(),
            "steve:internal:process-jobimpl",
            Conversions.convertFrontendFileToData(
              Collections.singletonList(req.getImplementation())),
            null,
            tags);
        }
      });

    fakeJob = Futures.transform(
      fakeJob,
      new AsyncFunction<Job, Job>()
      {
        public ListenableFuture<Job> apply(Job job)
        {
          final StatusBuilder status = new StatusBuilder();
          status.event = Status.Event.SUCCEEDED;
          status.machine = "frontend";
          status.timestamp = System.currentTimeMillis();

          return _db.addStatus(job.id, status.build());
        }
      });

    ListenableFuture<Frontend.Response> futureRes = Futures.transform(
      fakeJob,
      new Function<Job, Frontend.Response>()
      {
        public Frontend.Response apply(Job job)
        {
          return
            Frontend.Response.newBuilder()
            .setImplAdd(
              Frontend.ImplAddResponse.newBuilder()
              .setId(job.id))
            .build();
        }
      });

    return MoreFutures.compose(futureRes, new Runnable()
      {
        @Override
        public void run() throws SecurityException
        {
          tmpFile.delete();
        }
      });
  }

  /**
   * Handle a request to list job implementations.
   */
  private ListenableFuture<Frontend.Response> handleImplList(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    Frontend.ImplListRequest req)
  {
    final String user = getUser(httpRequest);
    return Futures.transform(
      _db.getJobImpl(user),
      new Function<Iterable<JobImpl>, Frontend.Response>()
      {
        public Frontend.Response apply(Iterable<JobImpl> impls)
        {
          Frontend.ImplListResponse.Builder resp = Frontend.ImplListResponse.newBuilder();
          for(JobImpl impl : impls)
          {
            if(!impl.id.startsWith("steve:internal:"))
              resp.addJobImpl(createImplInfo(impl));
          }

          return
            Frontend.Response.newBuilder()
            .setImplList(resp)
            .build();
        }
      });
  }

  private static Frontend.JobImplInfo createImplInfo(JobImpl impl)
  {
    Frontend.JobImplInfo.Builder info = Frontend.JobImplInfo.newBuilder();
    info.setId(impl.id);
    for(Map.Entry<String, String> entry : impl.metadata.entrySet())
    {
      info.addMetadata(Conversions.createFrontendParam(entry.getKey(), entry.getValue()));
    }
    return info.build();
  }
}
