package com.logicblox.steve.provision;

import com.amazonaws.regions.Region;
import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;
import com.amazonaws.services.ec2.model.*;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClient;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

public class Main
{
  private AmazonSQS sqs;
  private AmazonEC2 ec2;

  private String url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
  private String ami = "ami-d31603ba";
  private List<String> attrs = Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible");
  private double pctSpot = 0.75;
  private double spotPrice = 0.5;
  private String instanceType = "m2.xlarge";
  private String role = "steve-jobs-worker";
  private int totalNeeded = 0;
  private int maxInstances = 40;

  public Main()
  {
    setupAmazon();
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
    totalNeeded = Math.max(totalNeeded, maxInstances);

    int spotNeeded = (int) Math.ceil(pctSpot*totalNeeded) - spotCurrent;
    int odNeeded = totalNeeded - spotNeeded - odCurrent - spotCurrent;

    System.err.println(String.format("Number of current spot instances      : %d", spotCurrent));
    System.err.println(String.format("Number of current on-demand instances : %d", odCurrent));


    System.exit(1);
    if(spotNeeded > 0)
      createSpotInstances(spotNeeded);
    if(odNeeded > 0)
      createOnDemandInstances(odNeeded);
  }

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
    req.setLaunchSpecification(spec);


    RequestSpotInstancesResult res = ec2.requestSpotInstances(req);
  }

  public static void main(String args[])
  {
    Main m = new Main();
    m.go();
  }
}