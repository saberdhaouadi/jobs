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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.log4j.PatternLayout;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.Parameter;
import com.beust.jcommander.ParameterException;
import com.beust.jcommander.Parameters;
import com.google.common.io.ByteStreams;
import com.google.common.io.Closeables;
import com.google.protobuf.ByteString;
import com.google.protobuf.ExtensionRegistry;
import com.google.protobuf.TextFormat;
import com.googlecode.protobuf.format.JsonFormat;

import com.logicblox.bloxweb.UsageException;
import com.logicblox.common.logging.Logger;
import com.logicblox.common.logging.SystemDAppender;
import com.logicblox.common.logging.SystemDLevel;
import com.logicblox.common.logging.SystemDLogger;

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
  private final Logger _logger;

  public Main()
  {
    _logger = SystemDLogger.getLogger("SteveClient");
    _commander = new JCommander(new MainCommand());
    _commander.setProgramName("lb-steve-client");
    _commander.addCommand("create-job", new CreateJobCommand());
    _commander.addCommand("help", new HelpCommand());
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

    public abstract void invoke() throws Exception;
  }

  /**
   * Create job
   */
  @Parameters(commandDescription = "Create a new job")
  class CreateJobCommand extends Command
  {
    @Override
    public void invoke() throws Exception
    {
      System.out.println("woohoo");
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
