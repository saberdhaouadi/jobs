package com.logicblox.steve.client;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.log4j.PatternLayout;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;

import com.google.common.base.Function;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.ListeningScheduledExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonWriter;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.googlecode.protobuf.format.JsonFormat;

import com.logicblox.bloxweb.Encoding;
import com.logicblox.bloxweb.ProtoBufExchange;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.client.ClientConfigUtils;
import com.logicblox.bloxweb.client.ProtobufServiceClient;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.client.Transport;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.bloxweb.config.ConfigLocator;

import com.logicblox.common.Option;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDAppender;
import com.logicblox.common.logging.SystemDLevel;
import com.logicblox.common.logging.SystemDLogger;

import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.protocol.Frontend;

public class Main
{
  public static void main(String[] args)
  {
    org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
    SystemDAppender appender = new SystemDAppender(new PatternLayout("%d{ISO8601} %5p %-15c{1} - %m%n"));
    rootLogger.addAppender(appender);
    rootLogger.setLevel(SystemDLevel.INFO);

    try
    {
      Main main = new Main();
      main.execute(args);
    }
    catch(UsageException exc)
    {
      System.err.println("error: " + exc.getMessage());
      System.exit(1);
    }
    catch(Exception exc)
    {
      exc.printStackTrace();
      System.exit(1);
    }
    System.exit(0);
  }

  private JCommander _commander = new JCommander();
  private Config _config = null;
  private final Logger _logger;

  public Main()
  {
    _logger = SystemDLogger.getLogger("SteveClient");
    _commander = new JCommander(new MainCommand());
    _commander.setProgramName("lb-steve-client");
    _commander.addCommand("create-job", new CreateJobCommand());
    _commander.addCommand("status", new StatusCommand());
    _commander.addCommand("output", new OutputCommand());
    _commander.addCommand("upload-impl", new UploadJobImplCommand());
    _commander.addCommand("list-impl", new ListJobImplCommand());
    _commander.addCommand("help", new HelpCommand());

    File file1 = ConfigLocator.getDefaultConfigFile("lb-steve-client.config");
    File file2 = ConfigLocator.getDeploymentConfigFile("lb-steve-client.config", _logger);

    if(file1 != null)
      _config = new Config(file1, _config);
    if(file2 != null)
      _config = new Config(file2, _config);
  }

  class MainCommand
  {
    @Parameter(names = { "-h", "--help" }, description = "Print usage information", help = true)
    boolean help = false;
  }

  abstract class Command
  {
    @Parameter(names = { "-h", "--help" }, description = "Print usage information", help = true)
    boolean help = false;

    @Parameter(names = { "-c", "--config" }, description = "Configuration file", help = true)
    String config = null;

    public abstract void invoke() throws Exception;
  }

  protected URI createUniqueInputURI() throws URISyntaxException
  {
    String id = UUID.randomUUID().toString();
    return URI.create(_config.getStringError("default_input_prefix") + "/" + id);
  }

  protected ProtobufServiceClient getProtobufClient() throws URISyntaxException
  {
    String service = _config.getStringError("service");
    URI serviceUri = new URI(service);
    ServiceConnector connector = ServiceConnector.create(serviceUri.toString());

    // TODO support TCP configuration (timeouts, SSL etc)
    Transport transport = ClientConfigUtils.getTCPTransport();
    connector.setTransport(transport);
    connector.setEncoding(Encoding.JSON);
    connector.setGZIP(true);
    return connector.createProtobufClient();
  }

  private static String formatJSON(String json)
  {
    try
    {
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      JsonParser jp = new JsonParser();
      JsonElement je = jp.parse(json);
      return gson.toJson(je);
    }
    catch(Exception exc)
    {
      return json;
    }
  }

  /**
   * Create job
   */
  @Parameters(commandDescription = "Create a new job")
  class CreateJobCommand extends Command
  {
    @Parameter(names = {"--impl"}, description = "Job implementation identifier", required = true)
    String _impl;

    @Parameter(names = {"--corr"}, description = "Correlation identifier")
    String _correlation = null;

    @Parameter(names = {"-i", "--input"}, description = "S3 input file")
    List<String> _inputs;

    @Parameter(names = {"-o", "--output-prefix"}, description = "S3 URL prefix for output files", required = true)
    String _output;

    @Override
    public void invoke() throws Exception
    {
      ProtobufServiceClient client = getProtobufClient();
      Frontend.Request.Builder req = Frontend.Request.newBuilder();
      Frontend.Response.Builder resp = Frontend.Response.newBuilder();

      String clientId = UUID.randomUUID().toString();

      Frontend.JobCreateRequest.Builder createReq = 
        Frontend.JobCreateRequest.newBuilder()
        .setClientId(clientId)
        .setJobImpl(_impl)
        .setOutput(_output);

      for(String input : _inputs)
      {
        // TODO support automatically uploading files to S3 (using an --input-prefix option)
        // TODO support hashes as parameters or lookup in S3
        Frontend.File file = Frontend.File.newBuilder().setUrl(input).build();
        createReq.addInput(file);
      }

      req.setCreate(createReq);

      final ProtoBufExchange exchange = new ProtoBufExchange(req, resp, Option.<String>none());
      exchange.setRequestMessage(req.build());

      Futures.transform(client.postMessage(exchange), new AsyncFunction<Object, Object>()
      {        
        @Override
        public ListenableFuture<Object> apply(Object o) throws Exception
        {
          // TODO check for errors
          String json = exchange.getResponseJSON();
          json = formatJSON(json);
          System.out.println(json);
          return Futures.immediateFuture((Object) json);
        }
      }).get();
    }
  }

  /**
   * Status
   */
  @Parameters(commandDescription = "Check status of jobs")
  class StatusCommand extends Command
  {
    @Parameter(description = "Job identifiers", required = true)
    List<String> _ids;

    @Override
    public void invoke() throws Exception
    {
      ProtobufServiceClient client = getProtobufClient();

      for(String id : _ids)
      {
        Frontend.Request.Builder req =
          Frontend.Request.newBuilder()
          .setState(
            Frontend.StateRequest.newBuilder()
            .setId(id)
            .setDetail(true));

        Frontend.Response.Builder resp = Frontend.Response.newBuilder();

        final ProtoBufExchange exchange = new ProtoBufExchange(req, resp, Option.<String>none());
        exchange.setRequestMessage(req.build());

        Futures.transform(client.postMessage(exchange), new AsyncFunction<Object, Object>()
        {        
          @Override
          public ListenableFuture<Object> apply(Object o) throws Exception
          {
            Frontend.Response response = (Frontend.Response) exchange.getResponseMessage();
            // TODO bad requests return in crappy stacktraces
            // TODO check for errors
            for(Frontend.Status status : response.getState().getStatusList())
            {
              System.out.printf("%-30s %-12s %-20s %80s %n",
                Conversions.getISO8601(status.getTimestamp()),
                status.getStatusCode(),
                status.getMachine(),
                status.hasMessage() ? status.getMessage() : "");
            }

            return Futures.immediateFuture((Object) response);
          }
        }).get();
      }
    }
  }

  /**
   * Result
   */
  @Parameters(commandDescription = "Get output of a job")
  class OutputCommand extends Command
  {
    @Parameter(description = "Job identifiers", required = true)
    List<String> _ids;

    @Override
    public void invoke() throws Exception
    {
      ProtobufServiceClient client = getProtobufClient();
      for(String id : _ids)
      {
        Frontend.Request.Builder req =
          Frontend.Request.newBuilder()
          .setResult(
            Frontend.JobResultRequest.newBuilder()
            .setJobId(id));

        Frontend.Response.Builder resp = Frontend.Response.newBuilder();

        final ProtoBufExchange exchange = new ProtoBufExchange(req, resp, Option.<String>none());
        exchange.setRequestMessage(req.build());

        Futures.transform(client.postMessage(exchange), new AsyncFunction<Object, Object>()
        {        
          @Override
          public ListenableFuture<Object> apply(Object o) throws Exception
          {
            Frontend.Response response = (Frontend.Response) exchange.getResponseMessage();

            // TODO bad requests return in crappy stacktraces
            // TODO check for errors
            int max = 5;
            for(Frontend.File f : response.getResult().getOutputList())
            {
              max = Math.max(max, f.getUrl().length());
            }

            for(Frontend.File f : response.getResult().getOutputList())
            {
              System.out.printf("%-" + max + "s %s%n", f.getUrl(), f.getHash());
            }

            return Futures.immediateFuture((Object) response);
          }
        }).get();
      }
    }
  }

  /**
   * Upload job implementation
   */
  @Parameters(commandDescription = "Upload new job implementation")
  class UploadJobImplCommand extends Command
  {
    @Parameter(names = {"--impl"}, description = "Job implementation identifier", required = true)
    String _impl;

    @Parameter(
      names = {"-i", "--input"},
      description = "Job implementation tarball (S3 URL or local file)",
      required = true)
    String _input;

    @Override
    public void invoke() throws Exception
    {
      // TODO abstract this in a separate function for usage by normal create-job
      URI inputURI; 
      S3File inputS3File = null;
      if(_input.startsWith("s3://"))
      {
        inputURI = new URI(_input);
      }
      else
      {
        File inputFile = new File(_input);
        if(!inputFile.exists())
          throw new UsageException("Input file does not exist");

        S3Client s3client = S3Utils.createS3Client(_config);
        inputURI = createUniqueInputURI();
        inputS3File = s3client.upload(inputFile, inputURI).get();
      }

      Frontend.File.Builder fileBuilder = 
        Frontend.File.newBuilder()
        .setUrl(inputURI.toString());

      if(inputS3File != null)
        fileBuilder.setHash("etag:" + inputS3File.getETag());

      Frontend.Request.Builder req =
        Frontend.Request.newBuilder()
        .setImplAdd(
            Frontend.ImplAddRequest.newBuilder()
            .setId(_impl)
            .setImplementation(fileBuilder));

      Frontend.Response.Builder resp = Frontend.Response.newBuilder();

      final ProtoBufExchange exchange = new ProtoBufExchange(req, resp, Option.<String>none());
      exchange.setRequestMessage(req.build());

      Futures.transform(
        getProtobufClient().postMessage(exchange),
        new AsyncFunction<Object, Object>()
        {        
          @Override
          public ListenableFuture<Object> apply(Object o) throws Exception
          {
            // TODO check for errors
            String json = exchange.getResponseJSON();
            json = formatJSON(json);
            System.out.println(json);
            return Futures.immediateFuture((Object) json);
          }
        }).get();
    }
  }

  /**
   * List job implementation
   */
  @Parameters(commandDescription = "List job implementations")
  class ListJobImplCommand extends Command
  {
    @Override
    public void invoke() throws Exception
    {
      ProtobufServiceClient client = getProtobufClient();

      Frontend.Request.Builder req =
        Frontend.Request.newBuilder()
        .setImplList(Frontend.ImplListRequest.newBuilder());

      Frontend.Response.Builder resp = Frontend.Response.newBuilder();
      
      final ProtoBufExchange exchange = new ProtoBufExchange(req, resp, Option.<String>none());
      exchange.setRequestMessage(req.build());
      
      Futures.transform(client.postMessage(exchange), new AsyncFunction<Object, Object>()
      {        
        @Override
        public ListenableFuture<Object> apply(Object o) throws Exception
        {
          Frontend.Response response = (Frontend.Response) exchange.getResponseMessage();
          List<Frontend.ImplListResponse.ImplInfo> infos = response.getImplList().getJobImplList();

          for(Frontend.ImplListResponse.ImplInfo info : infos)
          {
            JsonWriter w = new JsonWriter(new OutputStreamWriter(System.out));

            w.beginObject()
              .name("id")
              .value(info.getId());

            for(Frontend.Param param : info.getMetadataList())
            {
              w.name(param.getKey()).value(param.getValue());
            }

            w.endObject();
            w.close();
          }

          return Futures.immediateFuture((Object) response);
        }
      }).get();
    }
  }
  
  /**
   * Help
   */
  @Parameters(commandDescription = "Print usage")
  class HelpCommand extends Command
  {
    @Parameter(description = "Commands")
    List<String> _commands;

    @Override
    public void invoke()
    {
      if(_commands == null)
        printUsage();
      else
      {
        for(String cmd : _commands)
        {
          printCommandUsage(cmd);
        }
      }
    }
  }

  public void execute(String[] args)
  {
    try
    {
      _commander.parse(args);
      String command = _commander.getParsedCommand();
      if(command != null)
      {
        Command cmd = (Command) _commander.getCommands().get(command).getObjects().get(0);
        if(cmd.help)
        {
          printCommandUsage(command);
          System.exit(1);
        }

        if(cmd.config != null)
          _config = new Config(new File(cmd.config), _config);

        cmd.invoke();
      }
      else
      {
        printUsage();
      }
    }
    catch(ParameterException exc)
    {
      System.err.println("error: " + exc.getMessage());
      System.err.println("");
      printUsage();
      System.exit(1);
    }
    catch(UsageException exc)
    {
      System.err.println("error: " + exc.getMessage());
      System.exit(1);
    }
    catch(Exception exc)
    {
      System.err.println("error: " + exc.getMessage());
      System.err.println("");
      exc.printStackTrace();
      System.exit(1);
    }
  }

  private void printOptions()
  {
    // Hack to avoid printing the commands, which are not formatted
    // correctly.
    JCommander tmp = new JCommander(new MainCommand());
    tmp.setProgramName("lb-guardian");

    // Hack to avoid printing the usage line, which is not correct in
    // this incomplete commander object.
    StringBuilder builder = new StringBuilder();
    tmp.usage(builder);
    String usage = builder.toString();
    String options = usage.substring(usage.indexOf('\n'));
    System.err.println(options);
  }

  private void printUsage()
  {
    System.err.println("Usage: lb-steve-client [options] command [command options]");
    printOptions();
    
    System.err.println("   Commands: ");
    for(String cmd : _commander.getCommands().keySet())
    {
      System.out.println("     " + padRight(23, ' ', cmd) + _commander.getCommandDescription(cmd));
    }
  }

  private static String padRight(int width, char c, String s)
  {
    StringBuffer buf = new StringBuffer(width);
    buf.append(s);
    for(int i = 0; i < width - s.length(); i++)
      buf.append(c);
    return buf.toString();
  }

  private void printCommandUsage(String command)
  {
    StringBuilder builder = new StringBuilder();
    _commander.usage(command, builder);
    System.err.println(builder.toString());
  }
}
