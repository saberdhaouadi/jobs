package com.logicblox.steve.provision;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;
import org.apache.commons.cli.*;

import java.util.*;

public class Main
{
  private AmazonSQS sqs;
  private AmazonEC2 ec2;

  private static String url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  private static String ami = "ami-a3425aca";
  private static List<String> attrs = Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible");
  private static double pctSpot = 0.75;
  private static double spotPrice = 0.5;
  private static String instanceType = "m2.xlarge";
  private static String role = "steve-jobs-worker";
  private static int totalNeeded = 0;
  private static int maxInstances = 40;

  public Main()
  {
    setupAmazon();
  }

  public static void parseArgs(String args[])
  {
    Options options = new Options();

    options.addOption(OptionBuilder.withLongOpt("queue")
            .withDescription("Job queue URL")
            .hasArg()
            .withArgName("URL")
            .create());

    options.addOption(OptionBuilder.withLongOpt("ami")
            .withDescription("Amazon Machine Image ID")
            .hasArg()
            .withArgName("AMI")
            .create());

    options.addOption(OptionBuilder.withLongOpt("percentage-spot")
            .withDescription("Percentage of spot instance of total")
            .hasArg()
            .withArgName("percentage")
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

    options.addOption(OptionBuilder.withLongOpt("total")
            .withDescription("Total number of instances to create")
            .hasArg()
            .withArgName("number")
            .withType(Number.class)
            .create());

    CommandLineParser parser = new BasicParser();
    try {
      CommandLine _cmdline = parser.parse( options, args );
      if (_cmdline.hasOption("queue"))
        url = _cmdline.getOptionValue("queue");
      if (_cmdline.hasOption("ami"))
        ami = _cmdline.getOptionValue("ami");
      if (_cmdline.hasOption("role"))
        role = _cmdline.getOptionValue("role");
      if (_cmdline.hasOption("instance-type"))
        instanceType = _cmdline.getOptionValue("instance-type");

      if (_cmdline.hasOption("total"))
        totalNeeded = ((Number)_cmdline.getParsedOptionValue("total")).intValue();
      if (_cmdline.hasOption("max"))
        maxInstances = ((Number)_cmdline.getParsedOptionValue("max")).intValue();
      if (_cmdline.hasOption("spot-price"))
        spotPrice = ((Number)_cmdline.getParsedOptionValue("spot-price")).doubleValue();
      if (_cmdline.hasOption("percentage-spot"))
        pctSpot = ((Number)_cmdline.getParsedOptionValue("percentage-spot")).doubleValue();

    }
    catch( ParseException exp ) {
      System.err.println( "Error: " + exp.getMessage() );
      HelpFormatter formatter = new HelpFormatter();
      formatter.printHelp( "lb-steve-provisioner", options );
      System.exit(1);
    }
  }



  private void setupAmazon()
  {
    sqs = new AmazonSQSClient();
    sqs.setRegion(Region.getRegion(Regions.US_EAST_1));

    ec2 = new AmazonEC2Client();
    ec2.setRegion(Region.getRegion(Regions.US_EAST_1));
  }

  public void go()
  {
    Map<String,String> result = sqs.getQueueAttributes(url, attrs).getAttributes();

    int waitingMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessages"));
    int busyMsgs = Integer.parseInt(result.get("ApproximateNumberOfMessagesNotVisible"));

    int spotCurrent = getNumberOfCurrentSpotInstances();
    int odCurrent = getNumberOfCurrentOnDemandInstances();

    if(totalNeeded == 0)
    {
      totalNeeded = (int) Math.ceil((busyMsgs + waitingMsgs) / 3f);
    }
    totalNeeded = Math.min(totalNeeded, maxInstances);

    int spotNeeded = (int) Math.ceil(pctSpot*totalNeeded) - spotCurrent;
    int odNeeded = totalNeeded - spotNeeded - odCurrent - spotCurrent;

    System.err.println(String.format("Number of current spot instances      : %d", spotCurrent));
    System.err.println(String.format("Number of current on-demand instances : %d", odCurrent));


    //System.exit(1);
    if(spotNeeded > 0)
      createSpotInstances(spotNeeded);
    if(odNeeded > 0)
      createOnDemandInstances(odNeeded);
  }

  // get number of spot instances that are not yet terminated
  private int getNumberOfCurrentSpotInstances()
  {
    int result = 0;
    DescribeInstancesRequest req = new DescribeInstancesRequest()
            .withFilters(
              new Filter().withName("instance-lifecycle").withValues("spot"),
              new Filter().withName("image-id").withValues(ami)
            );

    DescribeInstancesResult res = ec2.describeInstances(req);
    for(Reservation r : res.getReservations())
    {
      for(Instance i: r.getInstances())
      {
        if (! i.getState().getName().equals("terminated"))
        {
          result++;
        }
      }
    }

    DescribeSpotInstanceRequestsRequest spreq = new DescribeSpotInstanceRequestsRequest()
            .withFilters(
               new Filter().withName("launch.image-id").withValues(ami)
            );
    DescribeSpotInstanceRequestsResult spres = ec2.describeSpotInstanceRequests(spreq);
    for(SpotInstanceRequest r: spres.getSpotInstanceRequests())
    {
      if( r.getState().startsWith("pending") || r.getState().equals("fulfilled"))
      {
        result++;
      }
    }

    return result;
  }

  // get number of on-demand instances that are not yet terminated
  private int getNumberOfCurrentOnDemandInstances()
  {
    int result = 0;
    DescribeInstancesRequest req = new DescribeInstancesRequest()
            .withFilters(
                    new Filter().withName("image-id").withValues(ami)
            );

    DescribeInstancesResult res = ec2.describeInstances(req);
    for(Reservation r : res.getReservations())
    {
      for(Instance i: r.getInstances())
      {
        if (!i.getState().getName().equals("terminated") && i.getInstanceLifecycle() == null)
        {
          result++;
        }
      }
    }

    return result;
  }

  public void createOnDemandInstances(int nr)
  {
    System.err.println(String.format("Creating %d on-demand instances", nr));

    RunInstancesRequest req = new RunInstancesRequest();
    req.setMinCount(1);
    req.setMaxCount(nr);
    req.setImageId(ami);
    req.setInstanceType(instanceType);
    req.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(role));
    Collection<String> groups = new ArrayList<String>();
    groups.add("lb-steve-worker");
    req.setSecurityGroups(groups);

    RunInstancesResult res = ec2.runInstances(req);
  }

  public void createSpotInstances(int nr)
  {
    System.err.println(String.format("Creating %d spot instances", nr));

    RequestSpotInstancesRequest req = new RequestSpotInstancesRequest();
    req.setInstanceCount(nr);
    req.setSpotPrice(Double.toString(spotPrice));
    LaunchSpecification spec = new LaunchSpecification();
    spec.setImageId(ami);
    spec.setInstanceType(instanceType);
    spec.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(role));

    Collection<String> groups = new ArrayList<String>();
    groups.add("lb-steve-worker");
    spec.setSecurityGroups(groups);
    req.setLaunchSpecification(spec);

    RequestSpotInstancesResult res = ec2.requestSpotInstances(req);
  }

  public static void main(String args[])
  {
    parseArgs(args);
    Main m = new Main();
    m.go();
  }
}
