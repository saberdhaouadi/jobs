package com.logicblox.steve;

import java.io.File;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.commons.cli.BasicParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.OptionBuilder;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.log4j.PatternLayout;

import com.logicblox.bloxweb.BloxWebServer;
import com.logicblox.bloxweb.GlobalConfig;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.bloxweb.service.ConfigFiles;
import com.logicblox.bloxweb.config.ConfigLocator;
import com.logicblox.bloxweb.config.ConfigValidator;
import com.logicblox.bloxweb.config.ValidationMessage;
import com.logicblox.bloxweb.internal.Specification;
import com.logicblox.bloxweb.service.ServiceContext;
import com.logicblox.common.Option;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDAppender;
import com.logicblox.common.logging.SystemDLevel;
import com.logicblox.common.logging.SystemDLogger;

public class Main {
  public static void main(String[] args) {
    try {
      org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
      SystemDAppender appender = new SystemDAppender(new PatternLayout("%d{ISO8601} %5p %-18c{1} - %m%n"));
      rootLogger.addAppender(appender);
      rootLogger.setLevel(SystemDLevel.INFO);

      Logger logger = SystemDLogger.getLogger("Steve");

      final Main main = new Main(logger);

      if (main.processArgs(args)) {
        Collection<ValidationMessage> messages = ConfigValidator.validate(main._config);
        ConfigValidator.handleMessages(messages, logger);

        main._ctx = new ServiceContext(main._logger, new ConfigFiles(main._config,Option.<Config>none(),Option.<Config>none()));
        main._ctx.init();
        // Create job-auth realm for authentication
        Specification.Realm.Builder realm = Specification.Realm.newBuilder().setName("job-auth").setConfig("default-signature");
        main._ctx.getAuthenticationProvider().addRealm(realm.build());

        final BloxWebServer bloxwebServer = new BloxWebServer(
                Option.some(main._logDir),
                main._ctx);

        // start web server
        // TODO: exceptions in this thread gets caught how?
        ExecutorService webServerExecutor = Executors.newSingleThreadExecutor();
        webServerExecutor.submit(new Runnable() {
          @Override
          public void run() {
            bloxwebServer.run();
          }
        });

        main.loadServiceContext();
      } else {
        System.exit(1);
      }
    } catch (UsageException exc) {
      System.err.println("error: " + exc.getMessage());
      System.exit(1);
    } catch (Exception exc) {
      exc.printStackTrace();
      System.exit(1);
    }
  }

  private File _logDir;
  private Config _config;
  private Logger _logger;
  private ServiceContext _ctx;

  protected Main(Logger logger) {
    _logger = logger;
  }

  private void loadServiceContext() throws Exception {
    try {
      _ctx.getServiceMapScanner().rescan(_ctx, Collections.singletonList("steve"), true);
    } catch (Error e) {
      throw e;
    } catch (Exception e) {
      throw e;
    } catch (Throwable e) {
      throw new RuntimeException(e);
    }
  }

  private boolean processArgs(String[] args) throws Exception {
    //final String configFilename = "lb-steve-frontend.config";

    File file1 = ConfigLocator.getDefaultConfigFile("lb-web-server.config");
    File file2 = ConfigLocator.getDefaultConfigFile("lb-steve-frontend.config");
    File file3 = null;

    Options options = new Options();
    OptionBuilder.withLongOpt("config");
    OptionBuilder.withDescription("Configuration file");
    OptionBuilder.hasArg();
    OptionBuilder.withArgName("FILE");
    options.addOption(OptionBuilder.create());

    CommandLineParser parser = new BasicParser();
    try {
      CommandLine _cmdline = parser.parse(options, args);
      if (_cmdline.hasOption("config"))
        file3 = new File(_cmdline.getOptionValue("config"));
    } catch (ParseException exp) {
      System.err.println("Error: " + exp.getMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("lb-steve-frontend", options);
      System.exit(1);
    }

    if (file1 != null)
      _config = new Config(file1, _config);
    if (file2 != null)
      _config = new Config(file2, _config);
    if (file3 != null)
      _config = new Config(file3, _config);

    _logDir = new File(_config.getStringError("logdir_access"));
    if (!_logDir.exists())
      throw new UsageException("directory '" + _logDir.getPath() + "' does not exist");

    if (_config.getBoolError("debug")) {
      System.setProperty("org.eclipse.jetty.util.log.DEBUG", "true");
    }

    if (_config.contains("max_log_message_length"))
      GlobalConfig.setMaxLogMessageLength(
              _config.getIntError("max_log_message_length"));
    else
      GlobalConfig.setMaxLogMessageLength(-1);

    GlobalConfig.setLogMessages(_config.getBoolError("log_messages"));
    GlobalConfig.setLogHttp(_config.getBoolError("log_http"));
    GlobalConfig.setLogExceptionDetails(_config.getBoolError("log_exception_details"));
    GlobalConfig.setTcpAbortOnDisconnect(_config.getBoolError("tcp_abort_on_disconnect"));
    GlobalConfig.setDebug(_config.getBoolError("debug"));

    return true;
  }
}
