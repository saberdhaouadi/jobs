package com.logicblox.steve.provision;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import org.apache.commons.cli.*;
import org.apache.commons.codec.binary.Base64;

import java.util.*;
import java.lang.InterruptedException;

public class Main {
  private AmazonSQS sqs;
  private AmazonEC2 ec2;

  // TODO: make into required arguments
  private static String queue = "c3-xlarge";
  private static String incoming_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  private static String outgoing_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs-results";
  private static String ami = "ami-dfec32c9";
  private static String key = "rob";
  private static String region = "us-east-1";
  private static String s3Bucket = "steve-jobs";
  private static String instanceType = "c3.xlarge";
  private static String role = "steve-jobs-worker";
  private static String serviceUri = "http://localhost:8082/keys";

  private static List<String> attrs = Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible");
  private static double pctSpot = 0.9;
  private static double pctQueue = 0.6;
  private static double spotPrice = 0.6;
  private static int totalNeeded = 0;
  private static int maxInstances = 300;
  private static int minInstances = 0;
  private static boolean dryRun = true;

  private static Regions[] regions = new Regions[]{ Regions.US_EAST_1, Regions.US_WEST_1, Regions.US_WEST_2 };

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

    options.addOption(OptionBuilder.withLongOpt("dry-run")
            .withDescription("Whether to actually create the requested instances")
            .create());

    CommandLineParser parser = new BasicParser();
    try {
      CommandLine _cmdline = parser.parse(options, args);
      if (_cmdline.hasOption("queue"))
        queue = _cmdline.getOptionValue("queue");
      if (_cmdline.hasOption("bucket"))
        s3Bucket = _cmdline.getOptionValue("bucket");
      if (_cmdline.hasOption("incoming"))
        incoming_url = _cmdline.getOptionValue("incoming");
      if (_cmdline.hasOption("outgoing"))
        outgoing_url = _cmdline.getOptionValue("outgoing");
      if (_cmdline.hasOption("ami"))
        ami = _cmdline.getOptionValue("ami");
      if (_cmdline.hasOption("region"))
        region = _cmdline.getOptionValue("region");
      if (_cmdline.hasOption("key"))
        key = _cmdline.getOptionValue("key");
      if (_cmdline.hasOption("role"))
        role = _cmdline.getOptionValue("role");
      if (_cmdline.hasOption("instance-type"))
        instanceType = _cmdline.getOptionValue("instance-type");
      if (_cmdline.hasOption("key-service"))
        serviceUri = _cmdline.getOptionValue("key-service");

      if (_cmdline.hasOption("total"))
        totalNeeded = ((Number) _cmdline.getParsedOptionValue("total")).intValue();
      if (_cmdline.hasOption("max"))
        maxInstances = ((Number) _cmdline.getParsedOptionValue("max")).intValue();
      if (_cmdline.hasOption("min"))
        minInstances = ((Number) _cmdline.getParsedOptionValue("min")).intValue();

      if (_cmdline.hasOption("spot-price"))
        spotPrice = ((Number) _cmdline.getParsedOptionValue("spot-price")).doubleValue();
      if (_cmdline.hasOption("percentage-spot"))
        pctSpot = ((Number) _cmdline.getParsedOptionValue("percentage-spot")).doubleValue();
      if (_cmdline.hasOption("percentage-queue"))
        pctQueue = ((Number) _cmdline.getParsedOptionValue("percentage-queue")).doubleValue();

      if (maxInstances < totalNeeded) {
        maxInstances = totalNeeded;
      }

      dryRun = _cmdline.hasOption("dry-run");
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

    ec2 = new AmazonEC2Client();
    ec2.setRegion(Region.getRegion(Regions.fromName(region)));
  }

  public void go() {
    Map<String, String> result = sqs.getQueueAttributes(incoming_url, attrs).getAttributes();

    int waitingMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessages"));
    int busyMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessagesNotVisible"));

    // For workloads where only a few jobs are queued/running, start instance for each.
    // This will prevent the most common scenario, where we get notified by jobs that are
    // queued longer than an hour.
    if (busyMsgs + waitingMsgs <= 15) {
      minInstances = busyMsgs + waitingMsgs;
    }

    if (totalNeeded == 0) {
      totalNeeded = (int) Math.ceil((busyMsgs + waitingMsgs) * pctQueue);
    }
    totalNeeded = Math.min(totalNeeded, maxInstances);

    if (totalNeeded == 0) {
      return;
    }

    if (minInstances > totalNeeded) {
      totalNeeded = Math.min(totalNeeded, minInstances);
    }

    int spotCurrent = getNumberOfCurrentSpotInstances();
    int odCurrent = getNumberOfCurrentOnDemandInstances();

    int spotNeeded = (int) Math.ceil(pctSpot * totalNeeded) - spotCurrent;
    int odNeeded = totalNeeded - spotNeeded - odCurrent - spotCurrent;

    System.err.println(String.format("%s: Number of current spot instances      : %d", queue, spotCurrent));
    System.err.println(String.format("%s: Number of current on-demand instances : %d", queue, odCurrent));

    if (spotNeeded > 0)
      createSpotInstances(spotNeeded);
    if (odNeeded > 0)
      createOnDemandInstances(odNeeded);
  }

  private String getUserData() {
    return Base64.encodeBase64String(
            String.format("WORKERARGS=\"--bucket %s --incoming %s --outgoing %s --key-service %s\"",
                    s3Bucket,
                    incoming_url,
                    outgoing_url,
                    serviceUri
            ).getBytes()
    );
  }

  // get number of spot instances that are not yet terminated
  private int getNumberOfCurrentSpotInstances() {
    int result = 0;

    for(Regions region: regions) {
      AmazonEC2Client _ec2 = new AmazonEC2Client();
      _ec2.setRegion(Region.getRegion(region));

      DescribeSpotInstanceRequestsRequest spreq = new DescribeSpotInstanceRequestsRequest()
              .withFilters(
                      new Filter().withName("tag:S3Bucket").withValues(s3Bucket),
                      new Filter().withName("tag:IncomingQueue").withValues(incoming_url),
                      new Filter().withName("tag:OutgoingQueue").withValues(outgoing_url),
                      new Filter().withName("state").withValues("open", "active")
              );
      DescribeSpotInstanceRequestsResult spres = _ec2.describeSpotInstanceRequests(spreq);
      for (SpotInstanceRequest r : spres.getSpotInstanceRequests()) {
        result++;
      }
    }
    return result;
  }

  // get number of on-demand instances that are not yet terminated
  private int getNumberOfCurrentOnDemandInstances() {
    int result = 0;
    DescribeInstancesRequest req = new DescribeInstancesRequest()
            .withFilters(
                    new Filter().withName("tag:S3Bucket").withValues(s3Bucket),
                    new Filter().withName("tag:IncomingQueue").withValues(incoming_url),
                    new Filter().withName("tag:OutgoingQueue").withValues(outgoing_url)
            );

    DescribeInstancesResult res = ec2.describeInstances(req);
    for (Reservation r : res.getReservations()) {
      for (Instance i : r.getInstances()) {
        if (!i.getState().getName().equals("terminated") && i.getInstanceLifecycle() == null) {
          result++;
        }
      }
    }

    return result;
  }

  public void createOnDemandInstances(int nr) {
    System.err.println(String.format("Creating %d on-demand instances", nr));

    if (dryRun)
      return;

    RunInstancesRequest req = new RunInstancesRequest();
    req.setMinCount(1);
    req.setMaxCount(nr);
    req.setImageId(ami);
    req.setInstanceType(instanceType);
    req.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(role));
    req.setKeyName(key);
    req.setUserData(getUserData());

    Collection<String> groups = new ArrayList<String>();
    groups.add("admin");
    req.setSecurityGroups(groups);

    RunInstancesResult res = ec2.runInstances(req);

    try {
      Thread.sleep(60000);
    } catch (Exception e) {
    }

    for (Instance instance : res.getReservation().getInstances()) {
      createTags(instance.getInstanceId());
    }

  }

  private void createTags(String id) {
    CreateTagsRequest createTagsRequest = new CreateTagsRequest();
    createTagsRequest.withResources(id)
            .withTags(new Tag("Name", String.format("Worker [%s]", s3Bucket)))
            .withTags(new Tag("S3Bucket", s3Bucket))
            .withTags(new Tag("IncomingQueue", incoming_url))
            .withTags(new Tag("OutgoingQueue", outgoing_url))
    ;

    int retryCount = 0;
    while(retryCount < 10) {
      retryCount++;
      try {
        ec2.createTags(createTagsRequest);
        return;
      }
      catch(Exception e) {
        System.err.println("Error creating tags for "+id+" :"+e.getMessage());
        e.printStackTrace();
        try {
          Thread.sleep(10000);
        } catch (InterruptedException ie) {
        }
      }
    }
    System.err.println("Could not tag instance "+id);
  }

  public void createSpotInstances(int nr) {
    System.err.println(String.format("Creating %d spot instances", nr));

    if (dryRun)
      return;

    RequestSpotInstancesRequest req = new RequestSpotInstancesRequest();
    req.setInstanceCount(nr);
    req.setSpotPrice(Double.toString(spotPrice));
    LaunchSpecification spec = new LaunchSpecification();
    spec.setImageId(ami);
    spec.setInstanceType(instanceType);
    spec.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(role));
    spec.setKeyName(key);
    spec.setUserData(getUserData());

    Collection<String> groups = new ArrayList<String>();
    groups.add("admin");
    spec.setSecurityGroups(groups);
    req.setLaunchSpecification(spec);

    RequestSpotInstancesResult res = ec2.requestSpotInstances(req);
    try {
      Thread.sleep(60000);
    } catch (Exception e) {
    }
    for (SpotInstanceRequest sir : res.getSpotInstanceRequests()) {
      createTags(sir.getSpotInstanceRequestId());
    }
  }

  public static void main(String args[]) {
    parseArgs(args);
    Main m = new Main();
    m.go();
  }
}
