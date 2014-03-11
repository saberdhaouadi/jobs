package com.logicblox.steve.worker;

import org.apache.commons.exec.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

public class NixUtils {
  private final static String NIX_STORE_PATH = "/nix/store";
  private final static String NIX_LOG_PATH = "/nix/var/log/nix/drvs";

  public static String logPath(String drv)
  {
    String basename = FilenameUtils.getBaseName(drv);
    return String.format("%s/%s/%s.drv",NIX_LOG_PATH, basename.substring(0,2), basename.substring(2));
  }

  private static String streamToString(InputStream stream) throws IOException {
    StringWriter writer = new StringWriter();
    IOUtils.copy(stream, writer);

    return writer.toString().trim();
  }

  public static String nixInstantiate(String file) throws Exception {
    ProcessBuilder pb = new ProcessBuilder("nix-instantiate", file);

    Process p = pb.start();
    int exit = p.waitFor();
    if (exit != 0)
    {
      throw new Exception("nix-instantiate failed with exit code "+exit+"\n\n"+streamToString(p.getErrorStream()));
    }

    return streamToString(p.getInputStream());
  }

  public static void nixStoreRealise(String file, String job) throws Exception {
    // build up the command line to using a 'java.io.File'
    CommandLine commandLine = new CommandLine("nix-store");
    commandLine.addArgument("-r");
    commandLine.addArgument(file);
    commandLine.addArgument("-j");
    commandLine.addArgument("6");

    // create the executor and consider the exitValue '0' as success
    Executor executor = new DefaultExecutor();
    executor.setExitValue(0);

    // handle output
    SteveJobLogHandler outputStream = new SteveJobLogHandler(job);
    PumpStreamHandler streamHandler = new PumpStreamHandler(outputStream);
    executor.setStreamHandler(streamHandler);

    int exit;
    try {
      exit = executor.execute(commandLine);
    } catch (Exception ex) {
      throw ex;
    }

    if (exit != 0)
    {
      throw new Exception("nix-store failed with exit code "+exit);
    }
  }

}
