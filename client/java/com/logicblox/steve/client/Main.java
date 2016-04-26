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
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;

import org.apache.log4j.PatternLayout;
import org.apache.log4j.Level;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.beust.jcommander.converters.IParameterSplitter;

import com.google.common.base.Function;
import com.google.common.base.Functions;
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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.Message;
import com.google.protobuf.TextFormat;
import com.googlecode.protobuf.format.JsonFormat;

import com.logicblox.bloxweb.Encoding;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.client.ClientConfigUtils;
import com.logicblox.bloxweb.client.ProtobufServiceClient;
import com.logicblox.bloxweb.client.ServiceConnector;
import com.logicblox.bloxweb.client.Transport;
import com.logicblox.bloxweb.client.Transports;
import com.logicblox.bloxweb.client.SignUtils;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.bloxweb.config.ConfigLocator;
import com.logicblox.bloxweb.client.ServiceClientException;

import com.logicblox.common.Option;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDAppender;
import com.logicblox.common.logging.SystemDLevel;
import com.logicblox.common.logging.SystemDLogger;
import com.logicblox.concurrent.MoreFutures;

import com.logicblox.s3lib.S3Client;
import com.logicblox.s3lib.S3File;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.common.S3Utils;
import com.logicblox.steve.protocol.Frontend;

import java.security.PrivateKey;

public class Main {
  public static void main(String[] args) {
    org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
    rootLogger.setLevel(Level.WARN);

    try {
      Main main = new Main();
      main.execute(args);
    } catch (Exception exc) {
      exc.printStackTrace();
      System.exit(1);
    }
    System.exit(0);
  }

  private JCommander _commander = new JCommander();
  private Config _config = null;
  private final Logger _logger;
  private MainCommand _mainCmd = new MainCommand();
  private String _user = null;
  private String _keyFile = null;
  private S3Client _s3client;

  public Main() {
    _logger = SystemDLogger.getLogger("SteveClient");
    _commander = new JCommander(_mainCmd);
    _commander.setProgramName("lb-steve-client");
    _commander.addCommand("create-job", new CreateJobCommand());
    _commander.addCommand("status", new StatusCommand());
    _commander.addCommand("log", new LogCommand());
    _commander.addCommand("output", new OutputCommand());
    _commander.addCommand("upload-impl", new UploadJobImplCommand());
    _commander.addCommand("download-impl", new DownloadJobImplCommand());
    _commander.addCommand("list-impl", new ListJobImplCommand());
    _commander.addCommand("help", new HelpCommand());

    File file1 = ConfigLocator.getDefaultConfigFile("lb-steve-client.config");
    File file2 = ConfigLocator.getDeploymentConfigFile("lb-steve-client.config", _logger);

    if (file1 != null)
      _config = new Config(file1, _config);
    if (file2 != null)
      _config = new Config(file2, _config);

    _s3client = S3Utils.createS3Client(_config);
  }

  abstract class GlobalArgsCommand {
    @Parameter(names = {"-h", "--help"}, description = "Print usage information", help = true)
    boolean help = false;

    @Parameter(names = {"-c", "--config"}, description = "Configuration file")
    String config = null;

    @Parameter(names = {"-u", "--user"}, description = "User to use for authentication")
    String user = null;

    @Parameter(names = {"-k", "--key"}, description = "Private key to use for authentication")
    String keyFile = null;
  }

  public class MainCommand extends GlobalArgsCommand {
  }

  ;

  abstract class Command extends GlobalArgsCommand {
    public abstract void invoke() throws Exception;
  }

  protected URI createUniqueURI(String option, String base) throws URISyntaxException, UsageException {
    String id = UUID.randomUUID().toString();
    String optionValue = _config.getStringError(option);
    if(! optionValue.startsWith("s3://") ) {
       throw new UsageException("Incorrect option '"+option+" = "+optionValue+"', should be a S3 URL.");
    }
    return URI.create(optionValue + "/" + id + (base != null ? "/" + base : ""));
  }

  protected URI createUniqueInputURI(String base) throws URISyntaxException, UsageException {
    return createUniqueURI("default_input_prefix", base);
  }

  protected URI createUniqueOutputPrefixURI() throws URISyntaxException, UsageException {
    return createUniqueURI("default_output_prefix", null);
  }

  /**
   * Transparantly uploads input to S3 if it is a local file.
   */
  protected ListenableFuture<List<Frontend.File>> createInput(String input, String _inputEncryptionKey) throws Exception {
    // TODO support hashes as parameters or lookup in S3
    // TODO should we delete the input or rely on an automatic retention policy on the bucket?
    if (input.startsWith("s3://")) {
      Frontend.File.Builder fileBuilder =
              Frontend.File.newBuilder()
                      .setUrl(input);

      return Futures.immediateFuture(
              Collections.singletonList(
                      fileBuilder.build()));
    } else {
      File inputFile = new File(input);
      if (!inputFile.exists())
        throw new UsageException("Input file does not exist");

      if (inputFile.isDirectory()) {
        return Futures.transform(
                _s3client.uploadDirectory(inputFile, createUniqueInputURI(inputFile.getName()), _inputEncryptionKey),
                new Function<List<S3File>, List<Frontend.File>>() {
                  public List<Frontend.File> apply(List<S3File> files) {
                    List<Frontend.File> result = new ArrayList<Frontend.File>();
                    for (S3File f : files)
                      result.add(Conversions.convertToFrontendFile(f));
                    return result;
                  }
                });
      } else {
        return Futures.transform(
                _s3client.upload(inputFile, createUniqueInputURI(inputFile.getName()), _inputEncryptionKey),
                new Function<S3File, List<Frontend.File>>() {
                  public List<Frontend.File> apply(S3File file) {
                    return Collections.singletonList(Conversions.convertToFrontendFile(file));
                  }
                });
      }
    }
  }

  private String getAuthOption(String opt) throws UsageException {
    if (!_config.hasSection("auth"))
      throw new UsageException("Authentication configuration section not found.");
    return _config.getSection("auth").getStringError(opt);
  }

  protected ProtobufServiceClient getProtobufClient()
          throws URISyntaxException, UsageException {
    String service = _config.getStringError("service");
    URI serviceUri = new URI(service);
    ServiceConnector connector = ServiceConnector.create(serviceUri.toString());

    String user = _user;
    String keyFile = _keyFile;

    if (user == null)
      user = getAuthOption("user");
    if (keyFile == null)
      keyFile = getAuthOption("key_file");

    PrivateKey key;
    try {
      key = SignUtils.readPrivateKeyFromPEM(new FileReader(keyFile));
      connector.setTransport(Transports.sign(Transports.tcp(), user, key));
      connector.setEncoding(Encoding.JSON);
      connector.setGZIP(true);
      return connector.createProtobufClient();
    } catch (Exception e) {
      throw new UsageException("Could not load key file from " + keyFile + ": " + e.getMessage());
    }
  }

  protected SteveClientInterface getSteveClient()
          throws URISyntaxException {
    return new SteveClient(getProtobufClient(), Executors.newScheduledThreadPool(25));
  }

  private static String formatJSON(String json) {
    try {
      Gson gson = new GsonBuilder().setPrettyPrinting().create();
      JsonParser jp = new JsonParser();
      JsonElement je = jp.parse(json);
      return gson.toJson(je);
    } catch (Exception exc) {
      return json;
    }
  }

  /**
   * Create job
   */
  @Parameters(commandDescription = "Create a new job")
  class CreateJobCommand extends Command {
    @Parameter(names = {"--impl"}, description = "Job implementation identifier", required = true)
    String _impl;

    @Parameter(names = {"--corr"}, description = "Correlation identifier")
    String _correlation = null;

    @Parameter(names = {"--queue"}, description = "Job queue to use")
    String _queue = null;

    @Parameter(names = {"--timeout"}, description = "Timeout in seconds")
    long _timeout = 0;

    @Parameter(
            names = {"-m", "--metadata"},
            description = "Metadata of the form key=value ",
            variableArity = true,
            splitter = NoSplitter.class)
    List<String> _metadata = new ArrayList<String>();

    @Parameter(
            names = {"-i", "--input"},
            description = "Local or S3 input file (S3 files use s3://bucket/key URLs)")
    List<String> _inputs;

    @Parameter(
            names = {"--input-key"},
            description = "Key to use for encrypting of local file inputs for the job.")
    String _inputEncryptionKey = null;

    @Parameter(
            names = {"-o", "--output"},
            description = "Output of job, to be stored in either a local directory, single output file, " +
                    "or S3 output prefix (S3 files use s3://bucket/key URLs). If local output is requested, " +
                    "then the S3 default_output_prefix will be used to store the outputs")
    String _output;


    @Parameter(
            names = {"--output-key"},
            description = "Key that LB Jobs should use for encrypting the results of the job.")
    String _outputEncryptionKey = null;

    @Parameter(
            names = {"--wait"},
            description = "Wait for completion of the job by polling for the result")
    boolean _wait = false;

    @Parameter(
            names = {"--poll-delay"},
            description = "Delay in seconds for polling for the result")
    long _pollDelay = 5;

    @Override
    public void invoke() throws Exception {
      System.out.println(_metadata);
      // Collect inputs, uploading local files to S3 if needed.
      List<ListenableFuture<List<Frontend.File>>> inputFutures =
              new ArrayList<ListenableFuture<List<Frontend.File>>>();
      if (_inputs != null) {
        for (String input : _inputs) {
          inputFutures.add(createInput(input, _inputEncryptionKey));
        }
      }
      Iterable<Frontend.File> inputs = MoreFutures.concat(Futures.allAsList(inputFutures)).get();

      // Output is optional. If no output is specified, then we create
      // a unique location in the default output prefix.
      if (_output == null)
        _output = createUniqueOutputPrefixURI().toString();

      URI outputPrefix;
      final boolean autoDownload = !_output.startsWith("s3://");
      if (autoDownload) {
        outputPrefix = createUniqueOutputPrefixURI();

        // If the output is to be stored locally, then we
        // automatically wait for completion (can't do anything else)
        _wait = true;
      } else
        outputPrefix = URI.create(_output);

      // Handle metadata that is also offered as explicit options
      if (_timeout != 0)
        _metadata.add("timeout=" + _timeout);

      if (_correlation != null)
        _metadata.add("correlation-id=" + _correlation);

      if (_queue != null)
        _metadata.add("job-queue=" + _queue);

      final SteveClientInterface client = getSteveClient();

      Futures.transform(
              client.createJob(_impl, inputs, outputPrefix, _outputEncryptionKey, convertCommandLineMetadata(_metadata)),
              new AsyncFunction<String, Object>() {
                @Override
                public ListenableFuture<Object> apply(String id) throws Exception {
                  System.out.println(getJobIdAsJSON(id));

                  if (_wait) {
                    ListenableFuture<List<Frontend.File>> files = printResult(
                            client.waitForJob(id, _pollDelay, new IncrementalStateNotify()));

                    if (autoDownload)
                      files = downloadResult(_output, files);

                    return (ListenableFuture) files;
                  } else
                    return Futures.immediateFuture((Object) id);
                }
              }).get();
    }
  }

  /**
   * Status
   */
  @Parameters(commandDescription = "Check status of jobs")
  class StatusCommand extends Command {
    @Parameter(description = "Job identifiers", required = true)
    List<String> _ids;

    @Override
    public void invoke() throws Exception {
      SteveClientInterface client = getSteveClient();
      for (String id : _ids) {
        Futures.transform(
                client.getState(id),
                new Function<Frontend.State, Object>() {
                  @Override
                  public Object apply(Frontend.State state) {
                    System.out.println("State: " + state.getState());

                    List<Frontend.Status> list = state.getStatusList();
                    for (Frontend.Status status : list)
                      Printers.print(status);

                    return Futures.immediateFuture((Object) list);
                  }
                }).get();
      }
    }
  }

  /**
   * Log
   */
  @Parameters(commandDescription = "Get log of a job")
  class LogCommand extends Command {
    @Parameter(description = "Job identifier", required = true)
    List<String> _ids;

    @Override
    public void invoke() throws Exception {
      SteveClientInterface client = getSteveClient();
      if (_ids.size() != 1)
        throw new UsageException("Must specify exactly one job identifier.");

      Futures.transform(
              client.getLog(_ids.get(0)),
              new Function<String, Object>() {
                @Override
                public Object apply(String log) {
                  System.out.println(log);

                  return Futures.immediateFuture(null);
                }
              }).get();
    }
  }

  /**
   * Result
   */
  @Parameters(commandDescription = "Get output of a job")
  class OutputCommand extends Command {
    @Parameter(description = "Job identifiers", required = true)
    List<String> _ids;

    @Parameter(
            names = {"--wait"},
            description = "Wait for completion of the job by polling for the result")
    boolean _wait = false;

    @Parameter(
            names = {"--poll-delay"},
            description = "Delay in seconds for polling for the result")
    long _pollDelay = 5;

    @Parameter(
            names = {"-o", "--output"},
            description = "Download the job output to the specified file or directory")
    String _output;

    @Override
    public void invoke() throws Exception {
      SteveClientInterface client = getSteveClient();

      for (String id : _ids) {
        ListenableFuture<List<Frontend.File>> files;
        if (_wait)
          files = client.waitForJob(id, _pollDelay, new IncrementalStateNotify());
        else
          files = client.getResult(id);

        files = printResult(files);

        if (_output != null)
          files = downloadResult(_output, files);

        files.get();
      }
    }
  }

  private ListenableFuture<List<Frontend.File>> printResult(ListenableFuture<List<Frontend.File>> files) {
    return
            Futures.transform(
                    files,
                    new Function<List<Frontend.File>, List<Frontend.File>>() {
                      public List<Frontend.File> apply(List<Frontend.File> list) {
                        for (Frontend.File f : list)
                          System.out.println(Conversions.toJSON(f));
                        return list;
                      }
                    });
  }

  private ListenableFuture<List<Frontend.File>> downloadResult(final String output, ListenableFuture<List<Frontend.File>> future) {
    return
            Futures.transform(
                    future,
                    new AsyncFunction<List<Frontend.File>, List<Frontend.File>>() {
                      public ListenableFuture<List<Frontend.File>> apply(List<Frontend.File> list)
                              throws Exception {
                        return downloadResult(output, list);
                      }
                    });
  }

  private ListenableFuture<List<Frontend.File>> downloadResult(final String output, List<Frontend.File> files)
          throws IOException {
    Path p = Paths.get(output);
    if (Files.isDirectory(p) || output.endsWith("/") || files.size() > 1) {
      // Assume that we want to download the list of files to a directory.
      List<ListenableFuture<S3File>> downloads = new ArrayList<ListenableFuture<S3File>>();

      for (Frontend.File file : files) {
        Path targetFile = p.resolve(Conversions.getBasename(file));
        downloads.add(_s3client.download(targetFile.toFile(), URI.create(file.getUrl())));
      }

      return Futures.transform(Futures.allAsList(downloads), Functions.constant(files));
    } else {
      // Assume that we want to download to a single file
      // TOOD check the ETag from the download
      return
              Futures.transform(
                      _s3client.download(p.toFile(), URI.create(files.get(0).getUrl())),
                      Functions.constant(files));
    }
  }

  /**
   * Upload job implementation
   */
  @Parameters(commandDescription = "Upload new job implementation")
  class UploadJobImplCommand extends Command {
    @Parameter(
            names = {"--impl"},
            description = "Job implementation identifier",
            required = true)
    String _impl;

    @Parameter(
            names = {"-i", "--input"},
            description = "Job implementation tarball (S3 URL or local file)",
            required = true)
    String _input;

    @Parameter(
            names = {"-m", "--metadata"},
            description = "Metadata of the form key=value ",
            variableArity = true, 
            splitter = NoSplitter.class)
    List<String> _metadata;

    @Parameter(
            names = {"--wait"},
            description = "Wait for completion by polling for the result")
    boolean _wait = false;

    @Parameter(
            names = {"--poll-delay"},
            description = "Delay in seconds for polling for the result")
    long _pollDelay = 5;

    @Override
    public void invoke() throws Exception {
      final SteveClientInterface client = getSteveClient();

      if (new File(_input).isDirectory()) {
        File temp = File.createTempFile("impl", ".tar.gz");
        temp.deleteOnExit();

        File abs = new File(_input).getAbsoluteFile();
        _logger.info("Packaging directory " + _input);
        Process p = Runtime.getRuntime().exec("tar -C " + abs.getParent() + " -cvzf " + temp + " " + abs.toString().substring(abs.toString().lastIndexOf('/') + 1));
        p.waitFor();

        if (p.exitValue() != 0) {
          throw new Exception("Error packaging up input directory " + _input);
        }
        p.destroy();
        _input = temp.toString();
      }

      Futures.transform(
              client.addJobImpl(_impl, createInput(_input, null).get().get(0), convertCommandLineMetadata(_metadata)),
              new AsyncFunction<String, Object>() {
                @Override
                public ListenableFuture<Object> apply(String id) throws Exception {
                  System.out.println(getJobIdAsJSON(id));

                  if (_wait)
                    return (ListenableFuture) client.wait(id, _pollDelay, new IncrementalStateNotify());
                  else
                    return Futures.immediateFuture((Object) id);
                }
              }).get();
    }
  }

  /**
   * Download job implementation
   */
  @Parameters(commandDescription = "Download job implementation")
  class DownloadJobImplCommand extends Command {
    @Parameter(
            names = {"--impl"},
            description = "Job implementation identifier",
            required = true)
    String _impl;

    @Parameter(
            names = {"-o", "--output"},
            description = "Output location (S3 URL or local file)",
            required = true)
    String _output;

    @Override
    public void invoke() throws Exception {
      final SteveClientInterface client = getSteveClient();

      URI outputURI;
      final boolean autoDownload = !_output.startsWith("s3://");
      if (autoDownload)
        outputURI = createUniqueOutputPrefixURI();
      else
        outputURI = URI.create(_output);

      Futures.transform(client.copyJobImpl(_impl, outputURI),
              new AsyncFunction<String, Object>() {
                @Override
                public ListenableFuture<Object> apply(String id) throws Exception {
                  if(autoDownload)
                    return (ListenableFuture) _s3client.download(new File(_output),outputURI);
                  else
                    return Futures.immediateFuture((Object) id);
                }
              }).get();
    }
  }

  private static Iterable<Frontend.Param> convertCommandLineMetadata(List<String> pairs) {
    List<Frontend.Param> result = new ArrayList<Frontend.Param>();

    if (pairs != null) {
      for (String pair : pairs) {
        String key = pair.substring(0, pair.indexOf('='));
        String value = pair.substring(pair.indexOf('=') + 1);
        result.add(Conversions.createFrontendParam(key, value));
      }
    }

    return result;
  }

  private static String getJobIdAsJSON(String id) {
    JsonObject o = new JsonObject();
    o.addProperty("job_id", id);
    return new Gson().toJson(o);
  }

  /**
   * List job implementation
   */
  @Parameters(commandDescription = "List job implementations")
  class ListJobImplCommand extends Command {
    @Override
    public void invoke() throws Exception {
      SteveClientInterface client = getSteveClient();
      Futures.transform(
              client.getJobImplList(),
              new Function<List<Frontend.JobImplInfo>, Object>() {
                @Override
                public Object apply(List<Frontend.JobImplInfo> infos) {
                  for (Frontend.JobImplInfo info : infos)
                    System.out.println(Conversions.toJSON(info));

                  return infos;
                }
              }).get();
    }
  }

  /**
   * Help
   */
  @Parameters(commandDescription = "Print usage")
  class HelpCommand extends Command {
    @Parameter(description = "Commands")
    List<String> _commands;

    @Override
    public void invoke() {
      if (_commands == null)
        printUsage();
      else {
        for (String cmd : _commands) {
          printCommandUsage(cmd);
        }
      }
    }
  }

  public void execute(String[] args) {
    try {
      _commander.parse(args);
      String command = _commander.getParsedCommand();
      if (command != null) {
        Command cmd = (Command) _commander.getCommands().get(command).getObjects().get(0);
        if (cmd.help) {
          printCommandUsage(command);
          System.exit(1);
        }

        if (_mainCmd.config != null)
          _config = new Config(new File(_mainCmd.config), _config);

        if (cmd.config != null)
          _config = new Config(new File(cmd.config), _config);

        if (_mainCmd.user != null)
          _user = _mainCmd.user;

        if (cmd.user != null)
          _user = cmd.user;

        if (_mainCmd.keyFile != null)
          _keyFile = _mainCmd.keyFile;

        if (cmd.keyFile != null)
          _keyFile = cmd.keyFile;

        cmd.invoke();
      } else {
        printUsage();
      }
    } catch (ParameterException exc) {
      System.err.println("error: " + exc.getMessage());
      System.err.println("");
      printUsage();
      System.exit(1);
    } catch (UsageException exc) {
      System.err.println("error: " + exc.getMessage());
      System.exit(1);
    } catch (ExecutionException exc) {
      if (exc.getCause() instanceof SteveClientException) {
        SteveClientException e = (SteveClientException) exc.getCause();
        System.err.println(e.toJSON());
      } else
        exc.getCause().printStackTrace();
      System.exit(1);
    } catch (Exception exc) {
      System.err.println("error: " + exc.getMessage());
      System.err.println("");
      exc.printStackTrace();
      System.exit(1);
    }
  }

  private void printOptions() {
    // Hack to avoid printing the commands, which are not formatted
    // correctly.
    JCommander tmp = new JCommander(new MainCommand());
    tmp.setProgramName("lb-steve");

    // Hack to avoid printing the usage line, which is not correct in
    // this incomplete commander object.
    StringBuilder builder = new StringBuilder();
    tmp.usage(builder);
    String usage = builder.toString();
    String options = usage.substring(usage.indexOf('\n'));
    System.err.println(options);
  }

  private void printUsage() {
    System.err.println("Usage: lb-steve-client [options] command [command options]");
    printOptions();

    System.err.println("   Commands: ");
    for (String cmd : _commander.getCommands().keySet()) {
      System.out.println("     " + padRight(23, ' ', cmd) + _commander.getCommandDescription(cmd));
    }
  }

  private static String padRight(int width, char c, String s) {
    StringBuffer buf = new StringBuffer(width);
    buf.append(s);
    for (int i = 0; i < width - s.length(); i++)
      buf.append(c);
    return buf.toString();
  }

  private void printCommandUsage(String command) {
    StringBuilder builder = new StringBuilder();
    _commander.usage(command, builder);
    System.err.println(builder.toString());
  }

  public static class NoSplitter implements IParameterSplitter {
    @Override
    public List<String> split(String value) {
      return Collections.singletonList(value);
    }
  }
}
