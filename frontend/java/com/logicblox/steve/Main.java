package com.logicblox.steve;

import java.io.File;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.apache.log4j.PatternLayout;

import com.logicblox.bloxweb.BloxWebServer;
import com.logicblox.bloxweb.GlobalConfig;
import com.logicblox.bloxweb.UsageException;
import com.logicblox.bloxweb.config.Config;
import com.logicblox.bloxweb.config.ConfigLocator;
import com.logicblox.bloxweb.config.ConfigValidator;
import com.logicblox.bloxweb.config.ValidationMessage;
import com.logicblox.bloxweb.service.ServiceContext;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDAppender;
import com.logicblox.common.logging.SystemDLevel;
import com.logicblox.common.logging.SystemDLogger;

public class Main
{
  public static void main(String[] args)
  {
    try
    {
      org.apache.log4j.Logger rootLogger = org.apache.log4j.Logger.getRootLogger();
      SystemDAppender appender = new SystemDAppender(new PatternLayout("%d{ISO8601} %5p %-18c{1} - %m%n"));
      rootLogger.addAppender(appender);
      rootLogger.setLevel(SystemDLevel.INFO);

      Logger logger = SystemDLogger.getLogger("Steve");

      final Main main = new Main(logger);

      if(main.processArgs(args))
      {
        Collection<ValidationMessage> messages = ConfigValidator.validate(main._config);
        ConfigValidator.handleMessages(messages, logger);
                
        main.loadServiceContext();

        final BloxWebServer bloxwebServer = new BloxWebServer(
          main._logDir, 
          main._config,
          main._ctx,
          main._logger);

        // start web server
        // TODO: exceptions in this thread gets caught how?
        ExecutorService webServerExecutor = Executors.newSingleThreadExecutor();
        webServerExecutor.submit(new Runnable() {
          @Override
          public void run()
          {
            bloxwebServer.run();
          }
        });
      }
      else
      {
        System.exit(1);
      }
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
  }

  private File _logDir;
  private Config _config;
  private Logger _logger;
  private ServiceContext _ctx;

  protected Main(Logger logger)
  {
    _logger = logger;
  }

  private void loadServiceContext() throws Exception
  {
    _ctx = ServiceContext.fromConfig(_config, _logger);
  }

  private boolean processArgs(String[] args) throws Exception
  {
    // Construct an array of configuration files that will be used to
    // find configuration settings. Order:
    //
    // 1) Custom file specified via args[0]
    // 2) LB_DEPLOYMENT_HOME/config/lb-web-server.config
    // 3) BLOXWEB_HOME/config/lb-web-server.config

    final String configFilename = "lb-web-server.config";
    File defaultConfigFile = ConfigLocator.getDefaultConfigFile(configFilename);
    File deploymentConfigFile = ConfigLocator.getDeploymentConfigFile(configFilename, _logger);
    File customConfigFile = null;
    if(args.length == 1)
      customConfigFile = new File(args[0]);

    if(defaultConfigFile != null)
      _config = new Config(defaultConfigFile, _config);
    if(deploymentConfigFile != null)
      _config = new Config(deploymentConfigFile, _config);
    if(customConfigFile != null)
      _config = new Config(customConfigFile, _config);

    _logDir = new File(_config.getStringError("logdir_access"));
    if(!_logDir.exists())
      throw new UsageException("directory '" + _logDir.getPath() + "' does not exist");

    _config = new Config(new ByteArrayInputStream("scan_workspaces_on_startup = false".getBytes(StandardCharsets.UTF_8)), _config);

    if(_config.getBoolError("debug"))
    {
      System.setProperty("org.eclipse.jetty.util.log.DEBUG", "true"); 
    }

    if(_config.contains("max_log_message_length"))
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
