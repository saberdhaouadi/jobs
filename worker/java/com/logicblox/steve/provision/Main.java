package com.logicblox.steve.provision;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import org.apache.commons.cli.*;

import java.util.*;

public class Main {
  private AmazonSQS sqs;

  private static CommandLineArguments cmdArgs = new CommandLineArguments();

  private static List<String> attrs = Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible");
  private static int maxInstances = 300;


  public Main() {
    setupAmazon();
  }

  public static void parseArgs(String args[]) {
    Options options = new Options();

    options.addOption(OptionBuilder.withLongOpt("bucket")
            .withDescription("S3 bucket name")
            .hasArg()
            .withArgName("NAME")
            .create());

    options.addOption(OptionBuilder.withLongOpt("queue")
            .withDescription("Steve queue name")
            .hasArg()
            .withArgName("NAME")
            .create());

    options.addOption(OptionBuilder.withLongOpt("incoming")
            .withDescription("Job incoming queue URL")
            .hasArg()
            .withArgName("URL")
            .create());

    options.addOption(OptionBuilder.withLongOpt("outgoing")
            .withDescription("Job outgoing queue URL")
            .hasArg()
            .withArgName("URL")
            .create());

    options.addOption(OptionBuilder.withLongOpt("ami")
            .withDescription("Amazon Machine Image ID")
            .hasArg()
            .withArgName("AMI")
            .create());

    options.addOption(OptionBuilder.withLongOpt("region")
            .withDescription("Amazon EC2 region")
            .hasArg()
            .withArgName("REGION")
            .create());

    options.addOption(OptionBuilder.withLongOpt("key")
            .withDescription("Amazon EC2 keypair")
            .hasArg()
            .withArgName("KEY")
            .create());

    options.addOption(OptionBuilder.withLongOpt("key-service")
            .withDescription("Keys service URI")
            .hasArg()
            .withArgName("URI")
            .create());

    options.addOption(OptionBuilder.withLongOpt("percentage-queue")
            .withDescription("Set total instances to the given percentage of total messages that are in the queue. Only valid when --total is not used (value should be between 0 and 1)")
            .hasArg()
            .withArgName("percentage")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("percentage-spot")
            .withDescription("Percentage of spot instance of total (value should be between 0 and 1)")
            .hasArg()
            .withArgName("percentage")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("spot-price")
            .withDescription("Spot instance price")
            .hasArg()
            .withArgName("price")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("instance-type")
            .withDescription("EC2 instance type")
            .hasArg()
            .withArgName("type")
            .create());

    options.addOption(OptionBuilder.withLongOpt("role")
            .withDescription("IAM role to attach to instances")
            .hasArg()
            .withArgName("role")
            .create());

    options.addOption(OptionBuilder.withLongOpt("max")
            .withDescription("Maximum number of instances")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("max-delta")
            .withDescription("Maximum number of instances to be newly created")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("min")
            .withDescription("Minimum number of instances (only applies if any instances are needed)")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("total")
            .withDescription("Total number of instances to create")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("disk-size")
            .withDescription("Root disk size in GiB (for EBS backed images)")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    options.addOption(OptionBuilder.withLongOpt("subnet-id")
            .withDescription("Subnet ID")
            .hasArg()
            .withArgName("subnet")
            .create());

    options.addOption(OptionBuilder.withLongOpt("security-group")
            .withDescription("Security group")
            .hasArg()
            .withArgName("security group")
            .create());
    options.addOption(OptionBuilder.withLongOpt("backend")
            .withDescription("Cloud provider to use, aws or gcp")
            .hasArg()
            .withArgName("backend")
            .create());
    options.addOption(OptionBuilder.withLongOpt("project")
            .withDescription("Name of the project (required when using GCP backend)")
            .hasArg()
            .withArgName("project")
            .create());
    options.addOption(OptionBuilder.withLongOpt("service-account")
            .withDescription("service account to use in the worker (required when using GCP backend)")
            .hasArg()
            .withArgName("service account")
            .create());

    options.addOption(OptionBuilder.withLongOpt("dry-run")
            .withDescription("Whether to actually create the requested instances")
            .create());

    CommandLineParser parser = new BasicParser();
    try {
      CommandLine _cmdline = parser.parse(options, args);
      if (_cmdline.hasOption("queue"))
        cmdArgs.setQueue(_cmdline.getOptionValue("queue"));
      if (_cmdline.hasOption("bucket"))
        cmdArgs.setS3Bucket(_cmdline.getOptionValue("bucket"));
      if (_cmdline.hasOption("incoming"))
        cmdArgs.setIncoming_url(_cmdline.getOptionValue("incoming"));
      if (_cmdline.hasOption("outgoing"))
        cmdArgs.setOutgoing_url(_cmdline.getOptionValue("outgoing"));
      if (_cmdline.hasOption("ami"))
        cmdArgs.setAmi(_cmdline.getOptionValue("ami"));
      if (_cmdline.hasOption("region"))
        cmdArgs.setRegion(_cmdline.getOptionValue("region"));
      if (_cmdline.hasOption("key"))
        cmdArgs.setKey(_cmdline.getOptionValue("key"));
      if (_cmdline.hasOption("role"))
        cmdArgs.setRole(_cmdline.getOptionValue("role"));
      if (_cmdline.hasOption("instance-type"))
        cmdArgs.setInstanceType(_cmdline.getOptionValue("instance-type"));
      if (_cmdline.hasOption("key-service"))
        cmdArgs.setServiceUri(_cmdline.getOptionValue("key-service"));
      if (_cmdline.hasOption("subnet-id"))
        cmdArgs.setSubnetId(_cmdline.getOptionValue("subnet-id"));
      if (_cmdline.hasOption("security-group"))
        cmdArgs.setSecurityGroup(_cmdline.getOptionValue("security-group"));

      if (_cmdline.hasOption("total"))
        cmdArgs.setTotalNeeded(((Number) _cmdline.getParsedOptionValue("total")).intValue());
      if (_cmdline.hasOption("max"))
        cmdArgs.setMaxInstances(((Number) _cmdline.getParsedOptionValue("max")).intValue());
      if (_cmdline.hasOption("max-delta"))
        cmdArgs.setMaxDelta(((Number) _cmdline.getParsedOptionValue("max-delta")).intValue());
      if (_cmdline.hasOption("min"))
        cmdArgs.setMinInstances(((Number) _cmdline.getParsedOptionValue("min")).intValue());
      if (_cmdline.hasOption("disk-size"))
        cmdArgs.setDiskSize(((Number) _cmdline.getParsedOptionValue("disk-size")).intValue());

      if (_cmdline.hasOption("spot-price"))
        cmdArgs.setSpotPrice(((Number) _cmdline.getParsedOptionValue("spot-price")).doubleValue());
      if (_cmdline.hasOption("percentage-spot"))
        cmdArgs.setPctSpot(((Number) _cmdline.getParsedOptionValue("percentage-spot")).doubleValue());
      if (_cmdline.hasOption("percentage-queue"))
        cmdArgs.setPctQueue(((Number) _cmdline.getParsedOptionValue("percentage-queue")).doubleValue());

      if (_cmdline.hasOption("backend"))
        cmdArgs.setBackend(_cmdline.getOptionValue("backend"));
      if (_cmdline.hasOption("project"))
        cmdArgs.setProject(_cmdline.getOptionValue("project"));
      if (_cmdline.hasOption("service-account"))
        cmdArgs.setServiceAccount(_cmdline.getOptionValue("service-account"));

      if (cmdArgs.getMaxInstances() < cmdArgs.getTotalNeeded()) {
         cmdArgs.setMaxInstances(cmdArgs.getTotalNeeded());
      }

      if (cmdArgs.getBackend().toLowerCase() == "gcp" && cmdArgs.getProject().isEmpty() && cmdArgs.getServiceAccount.isEmpty())
          throw new MissingOptionException("You need to specify the name of the project when using GCP backend");

      cmdArgs.setDryRun(_cmdline.hasOption("dry-run"));
    } catch (ParseException exp) {
      System.err.println("Error: " + exp.getMessage());
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp("lb-steve-provisioner", options);
      System.exit(1);
    }
  }


  private void setupAmazon() {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));

  }

  public int CalculateTotalNeeded(){

    int totalNeeded = cmdArgs.getTotalNeeded();
    Map<String, String> result = sqs.getQueueAttributes(cmdArgs.getIncoming_url(), attrs).getAttributes();

    int waitingMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessages"));
    int busyMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessagesNotVisible"));

    // For workloads where only a few jobs are queued/running, start instance for each.
    // This will prevent the most common scenario, where we get notified by jobs that are
    // queued longer than an hour.
    int minInstances = 0;
    if (busyMsgs + waitingMsgs <= 15) {
      minInstances = busyMsgs + waitingMsgs;
    }

    if (totalNeeded == 0) {
      totalNeeded =  (int) Math.ceil((busyMsgs + waitingMsgs) * cmdArgs.getPctQueue());
    }
    totalNeeded = Math.min(totalNeeded, cmdArgs.getMaxInstances());

    if (totalNeeded == 0) {
      return totalNeeded;
    }

    if (minInstances > totalNeeded) {
      totalNeeded = Math.min(totalNeeded, minInstances);
    }
    return totalNeeded ;
  }

  public void go() {


    ProvisionerInterface backend;
    if(cmdArgs.getBackend().toLowerCase().equals("aws")){
      backend = new AWSProvisioner(cmdArgs);
    } else {
      backend = new GCEProvisioner(cmdArgs);
    }

    int totalNeeded = CalculateTotalNeeded();

    int spotCurrent = backend.getNumberOfCurrentSpotInstances();
    int odCurrent = backend.getNumberOfCurrentOnDemandInstances();

    int newNeeded = totalNeeded - spotCurrent - odCurrent;
    if (cmdArgs.getMaxDelta() != -1) {
      newNeeded = Math.min(newNeeded, cmdArgs.getMaxDelta());
    }

    int spotNeeded = (int) Math.ceil(cmdArgs.getPctSpot() * newNeeded);
    int odNeeded = newNeeded - spotNeeded;

    System.err.println(String.format("%s: Number of current spot instances      : %d", cmdArgs.getQueue(), spotCurrent));
    System.err.println(String.format("%s: Number of current on-demand instances : %d", cmdArgs.getQueue(), odCurrent));

    if (spotNeeded > 0)
      backend.createSpotInstances(spotNeeded);
    if (odNeeded > 0)
      backend.createOnDemandInstances(odNeeded);
  }


  public static void main(String args[]) {
    parseArgs(args);
    Main m = new Main();
    m.go();
  }
}
