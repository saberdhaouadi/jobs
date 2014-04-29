package com.logicblox.steve;

import java.net.URI;
import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.http.HttpException;
import org.eclipse.jetty.http.HttpStatus;

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

import com.logicblox.sqs.SQSException;
import com.logicblox.sqs.SQSClient;
import com.logicblox.sqs.SQSQueueHandle;
import com.logicblox.sqs.SQSClients;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.db.Database;
import com.logicblox.steve.db.DynamoJobState;
import com.logicblox.steve.db.FakeDatabase;
import com.logicblox.steve.db.Job;
import com.logicblox.steve.frontend.JobQueueClient;
import com.logicblox.steve.frontend.StatusQueueClient;
import com.logicblox.steve.protocol.Frontend;

public class SteveHandler extends ProtoBufHandler
{
  private Database _db;
  private JobQueueClient _jobQueue;

  public SteveHandler()
  {
    super("Steve");
  }

  @Override
  public void init(Section handlerConfig, ServiceConfig service)
  {
    super.init(handlerConfig, service);
    _db = new FakeDatabase(new DynamoJobState(handlerConfig.getParent(), _logger));

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
    boolean create = true;
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
    // same protocol...
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

    System.out.println(request.toString());

    if(request.hasCreate())
    {
      ListenableFuture<Frontend.Response> resp = handleCreate(httpRequest, httpResponse, request.getCreate());
      return MoreFutures.transferResponse(resp, exchange);
    }
    else if(request.hasState())
    {
      return Futures.immediateFailedFuture(new HttpException(HttpStatus.BAD_REQUEST_400, "Not yet implemented"));
    }
    else if(request.hasKill())
    {
      return Futures.immediateFailedFuture(new HttpException(HttpStatus.BAD_REQUEST_400, "Not yet implemented"));
    }
    else
    {
      return Futures.immediateFailedFuture(new HttpException(HttpStatus.BAD_REQUEST_400, "Request union has no request"));
    }
  }

  private ListenableFuture<Frontend.Response> handleCreate(
    HttpServletRequest httpRequest,
    HttpServletResponse httpResponse, 
    Frontend.CreateRequest req)
  {
    ListenableFuture<Job> job =
      _db.createJob(
        "martin", // TODO require authentication and use actual user
        req.getClientId(),
        req.getJobImpl(),
        Conversions.convertFrontendFileToData(req.getInputList()),
        req.getOutput());

    job = Futures.transform(job, new AsyncFunction<Job, Job>()
    {
      public ListenableFuture<Job> apply(Job j)
      {
        return _jobQueue.submit(j);
      }
    });

    return Futures.transform(
      job,
      new Function<Job, Frontend.Response>()
      {
        public Frontend.Response apply(Job job)
        {
          Frontend.Response.Builder response = Frontend.Response.newBuilder();
          response.setCreate(
            Frontend.CreateResponse.newBuilder()
            .setJobId(job.getId()));
          
          return response.build();
        }
      });
  }

}
