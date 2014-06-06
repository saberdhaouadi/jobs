package com.logicblox.steve;

import java.net.URI;
import java.net.URISyntaxException;
import java.io.IOException;
import java.io.File;
import java.util.UUID;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;

import com.amazonaws.services.s3.model.ObjectMetadata;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.Message;

import com.logicblox.bloxweb.HandlerValidationException;
import com.logicblox.bloxweb.InvalidRequestException;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.ProtoBufHandler;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.bloxweb.config.ConfigMap;
import com.logicblox.bloxweb.config.Section;
import com.logicblox.bloxweb.service.ServiceConfig;
import com.logicblox.concurrent.MoreFutures;

import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;

import com.logicblox.bloxweb.service.ServiceException;
import com.logicblox.bloxweb.SimpleErrorCode;

import com.logicblox.sqs.SQSException;
import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.sqs.SQSClients;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.db.DynamoJobState;
import com.logicblox.steve.db.FakeDatabase;
import com.logicblox.steve.db.Job;
import com.logicblox.steve.db.JobImpl;
import com.logicblox.steve.db.Status;
import com.logicblox.steve.frontend.JobQueueClient;
import com.logicblox.steve.frontend.StatusQueueClient;
import com.logicblox.steve.protocol.Frontend;

public class SteveHandler extends ProtoBufHandler
{
  private static final long MAX_IMPL_SIZE = 1;

  private Database _db;
  private JobQueueClient _jobQueue;
  private S3Client _s3client;
  private File _tmpDir;
  private String _jobImplPrefix;

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

    Section jobQueueConfig = handlerConfig.getParent().getSection("job-queue");
    Section statusQueueConfig = handlerConfig.getParent().getSection("status-queue");

    SQSClients sqsClients = new SQSClients();
    SQSClient jobClient = sqsClients.getSQSClient(jobQueueConfig);
    SQSClient statusClient = sqsClients.getSQSClient(statusQueueConfig);

    try
    {
      SQSQueueHandle jobQueue = getQueueFromConfig(jobClient, jobQueueConfig);
      _jobQueue = new JobQueueClient(jobClient, jobQueue);

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
    boolean create = false;
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

  @Override
  protected ListenableFuture<ProtoBufExchange> handle(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    ProtoBufExchange exchange)
  throws ServletException, IOException, InvalidProtocolBufferException, InvalidRequestException
  {
    Frontend.Request request = (Frontend.Request) exchange.getRequestMessage();

    // TODO remove debugging
    System.out.println(request.toString());

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
    else if(request.hasImplAdd())
    {
      resp = handleImplAdd(httpRequest, httpResponse, request.getImplAdd());
    }
    else
    {
      resp = Futures.immediateFailedFuture(
        new HttpException(HttpStatus.BAD_REQUEST_400, "Request union has no request"));
    }

    return MoreFutures.transferResponse(resp, exchange);
  }

  private ListenableFuture<Frontend.Response> handleCreate(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    Frontend.JobCreateRequest req)
  {
    // TODO require authentication and use actual user
    ListenableFuture<Job> job =
      _db.createJob(
        "martin",
        req.getClientId(),
        req.getJobImpl(),
        Conversions.convertFrontendFileToData(req.getInputList()),
        req.getOutput());

    // Once we have the job stored in the database, submit it to the queue
    job = Futures.transform(job, new AsyncFunction<Job, Job>()
    {
      public ListenableFuture<Job> apply(Job j)
      {
        return _jobQueue.submit(j);
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
            .setJobId(job.getId()));
          
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
          Frontend.StateResponse.Builder b = Frontend.StateResponse.newBuilder();

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

          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setState(b);
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

          if(!job.isSucceeded())
          {
            // TODO Better error (check if failed, executing etc)
            return Futures.immediateFailedFuture(
              new HttpException(HttpStatus.BAD_REQUEST_400, "Job has no output yet"));
          }

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

  private ListenableFuture<Frontend.Response> handleImplAdd(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    final Frontend.ImplAddRequest req)
  throws IOException
  {
    // TODO finally remove the temporary file
    final File tmpFile = File.createTempFile("jobimpl", null, _tmpDir);
    final String id = UUID.randomUUID().toString();

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

    // Check the metadata, and if we're okay, then download the file
    // from S3 to a temporary file
    ListenableFuture<S3File> inputFile = Futures.transform(
      metadata,
      new AsyncFunction<ObjectMetadata, S3File>()
      {
        public ListenableFuture<S3File> apply(ObjectMetadata m) throws IOException
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

    ListenableFuture<JobImpl> jobImpl = Futures.transform(
      newFile,
      new AsyncFunction<S3File, JobImpl>()
      {
        public ListenableFuture<JobImpl> apply(S3File input) throws IOException
        {
          // TODO use actual authenticated user
          return _db.setJobImpl(
            "martin",
            req.getId(),
            Conversions.convertS3FileToData(input));
        }
      });

    return Futures.transform(
      jobImpl,
      new Function<JobImpl, Frontend.Response>()
      {
        public Frontend.Response apply(JobImpl impl)
        {
          // TODO revise server-side implementation to correctly use an
          // identifier (not TODO)
          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setImplAdd(
            Frontend.ImplAddResponse.newBuilder()
            .setId("TODO"));
          
          return response.build();
        }
      });
  }
}
