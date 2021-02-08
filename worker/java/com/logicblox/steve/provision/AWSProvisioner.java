package com.logicblox.steve.provision;

import com.amazonaws.regions.Regions;
import com.amazonaws.services.ec2.model.*;
import org.apache.commons.codec.binary.Base64;
import com.amazonaws.regions.Region;
import com.amazonaws.services.ec2.AmazonEC2;
import com.amazonaws.services.ec2.AmazonEC2Client;

import java.util.*;
import java.util.ArrayList;
import java.util.Collection;

public class AWSProvisioner implements ProvisionerInterface {

    private static Regions[] regions = new Regions[]{ Regions.US_EAST_1, Regions.US_EAST_2, Regions.US_WEST_1, Regions.US_WEST_2 };
    private AmazonEC2 ec2;

    public AWSProvisioner(CommandLineArguments cmdArgs){
        this.setCmdArgs(cmdArgs);
        ec2 = new AmazonEC2Client();
        ec2.setRegion(Region.getRegion(Regions.fromName(cmdArgs.getRegion())));
    }

    CommandLineArguments cmdArgs;

    public void createSpotInstances(int nr) {

        System.err.println(String.format("Creating %d spot instances", nr));
        String workersvpcId = "";

        Collection<Tag> tags = new ArrayList<Tag>();
        Collection<SpotFleetTagSpecification> tagspeclist = new ArrayList<SpotFleetTagSpecification>();
        tags.add(new Tag("Name", String.format("Worker [%s]", cmdArgs.getS3Bucket())));
        tags.add(new Tag("S3Bucket", cmdArgs.getS3Bucket()));
        tags.add(new Tag("IncomingQueue", cmdArgs.getIncoming_url()));
        tags.add(new Tag("OutgoingQueue", cmdArgs.getOutgoing_url()));

        DescribeVpcsRequest vpcsrequest = new DescribeVpcsRequest().withFilters(new Filter().withName("tag:Name").withValues("Workers VPC"));
        DescribeVpcsResult vpcsresult = ec2.describeVpcs(vpcsrequest);
        List<Vpc> vpcresults = vpcsresult.getVpcs();
        for (Vpc v : vpcresults) {
          workersvpcId = v.getVpcId();
          System.out.println(workersvpcId);
        }

        List<String> WorkersSubnetsList = new ArrayList<String>();
        DescribeSubnetsRequest subnetsrequest = new DescribeSubnetsRequest()
                    .withFilters(
                            new Filter().withName("vpc-id").withValues(workersvpcId),
                            new Filter().withName("tag:Name").withValues("workers")
                    );
        DescribeSubnetsResult subnetsresult = ec2.describeSubnets(subnetsrequest);
        List<Subnet> workerssubnets = subnetsresult.getSubnets();
        for (Subnet sub : workerssubnets) {
            WorkersSubnetsList.add(sub.getSubnetId());
          }
        System.out.println(WorkersSubnetsList);
        //Getting admin security group Id
        DescribeSecurityGroupsRequest securitygroupsrequest = new DescribeSecurityGroupsRequest().withFilters(
                                                                                                       new Filter().withName("vpc-id").withValues(workersvpcId),
                                                                                                       new Filter().withName("group-name").withValues(cmdArgs.getSecurityGroup())
                                                                                                  );
        DescribeSecurityGroupsResult securitygroupsresult = ec2.describeSecurityGroups(securitygroupsrequest);
        Collection <SecurityGroup> adminsecuritygroups = securitygroupsresult.getSecurityGroups();
        GroupIdentifier groupidf = new GroupIdentifier();
        for (SecurityGroup g : adminsecuritygroups) {
            groupidf.setGroupId(g.getGroupId());
        }

        if (cmdArgs.isDryRun())
        return;

        RequestSpotFleetRequest request = new RequestSpotFleetRequest();

        SpotFleetRequestConfigData fleetconfig = new SpotFleetRequestConfigData();

        //fix spot fleet role argument
        fleetconfig.setIamFleetRole(cmdArgs.getSpotFleetRole());
        fleetconfig.setSpotPrice(Double.toString(cmdArgs.getSpotPrice()));
        fleetconfig.setTargetCapacity(nr);
        fleetconfig.setType("request");

        fleetconfig.setAllocationStrategy("capacityOptimized");

        Collection<SpotFleetLaunchSpecification> LaunchSpecs = new ArrayList<SpotFleetLaunchSpecification>();

        Collection<GroupIdentifier> identgroups = new ArrayList<GroupIdentifier>();
        identgroups.add(groupidf);

        SpotFleetTagSpecification fleettagsspec = new SpotFleetTagSpecification();
        fleettagsspec.setTags(tags);
        fleettagsspec.setResourceType("instance");
        tagspeclist.add(fleettagsspec);

        for (String sp : WorkersSubnetsList)

        {
         SpotFleetLaunchSpecification fleetspec = new SpotFleetLaunchSpecification();

         //fix user data
         fleetspec.setKeyName(cmdArgs.getKey());
         fleetspec.setImageId(cmdArgs.getAmi());
         fleetspec.setInstanceType(cmdArgs.getInstanceType());
         fleetspec.setUserData(getUserData());
         fleetspec.setSubnetId(sp);

         IamInstanceProfileSpecification profilespec = new IamInstanceProfileSpecification();
         profilespec.setName(cmdArgs.getRole());
         fleetspec.setIamInstanceProfile(profilespec);
         fleetspec.setSecurityGroups(identgroups);
         fleetspec.setTagSpecifications(tagspeclist);
         LaunchSpecs.add(fleetspec);

        }

        fleetconfig.setLaunchSpecifications(LaunchSpecs);
        request.setSpotFleetRequestConfig(fleetconfig);
        RequestSpotFleetResult response = ec2.requestSpotFleet(request);
        String fleetID = response.getSpotFleetRequestId();
        System.out.println(String.format("Spot fleet request ID %s",fleetID));
        //TODO: Work on EC2fleet code
    }

    public void createOnDemandInstances(int nr) {
        System.err.println(String.format("Creating %d on-demand instances", nr));
        if (cmdArgs.isDryRun())
            return;

        RunInstancesRequest req = new RunInstancesRequest();
        req.setMinCount(1);
        req.setMaxCount(nr);
        req.setImageId(cmdArgs.getAmi());
        req.setInstanceType(cmdArgs.getInstanceType());
        req.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(cmdArgs.getRole()));
        req.setKeyName(cmdArgs.getKey());
        req.setUserData(getUserData());

        String workersvpcId = "";
        DescribeVpcsRequest vpcsrequest = new DescribeVpcsRequest().withFilters(new Filter().withName("tag:Name").withValues("Workers VPC"));
        DescribeVpcsResult vpcsresult = ec2.describeVpcs(vpcsrequest);
        List<Vpc> vpcresults = vpcsresult.getVpcs();
        for (Vpc v : vpcresults) {
          workersvpcId = v.getVpcId();
        }

        List<String> WorkersSubnetsList = new ArrayList<String>();
        DescribeSubnetsRequest subnetsrequest = new DescribeSubnetsRequest()
                    .withFilters(
                            new Filter().withName("vpc-id").withValues(workersvpcId),
                            new Filter().withName("tag:Name").withValues("workers")
                    );
        DescribeSubnetsResult subnetsresult = ec2.describeSubnets(subnetsrequest);
        List<Subnet> workerssubnets = subnetsresult.getSubnets();
        for (Subnet sub : workerssubnets) {
            WorkersSubnetsList.add(sub.getSubnetId());
          }

        System.out.println(WorkersSubnetsList.get(2));
        String OndemandSubnet = WorkersSubnetsList.get(2);
        //Getting admin security group Id
        DescribeSecurityGroupsRequest securitygroupsrequest = new DescribeSecurityGroupsRequest().withFilters(
                                                                                                       new Filter().withName("vpc-id").withValues(workersvpcId),
                                                                                                       new Filter().withName("group-name").withValues(cmdArgs.getSecurityGroup())
                                                                                                  );
        DescribeSecurityGroupsResult securitygroupsresult = ec2.describeSecurityGroups(securitygroupsrequest);
        Collection <SecurityGroup> adminsecuritygroups = securitygroupsresult.getSecurityGroups();
        List<String> adminOndemandgroups = new ArrayList<String>();
        for (SecurityGroup sg : adminsecuritygroups) {
             adminOndemandgroups.add(sg.getGroupId());
           }

        req.setSecurityGroupIds(adminOndemandgroups);
        req.setSubnetId(OndemandSubnet);

        if(cmdArgs.getDiskSize() != 0) {
            BlockDeviceMapping blockDeviceMapping = new BlockDeviceMapping();
            blockDeviceMapping.setDeviceName("/dev/sda1");

            EbsBlockDevice ebs = new EbsBlockDevice();
            ebs.setVolumeSize(cmdArgs.getDiskSize());
            blockDeviceMapping.setEbs(ebs);

            ArrayList<BlockDeviceMapping> blockList = new ArrayList<BlockDeviceMapping>();
            blockList.add(blockDeviceMapping);

            req.setBlockDeviceMappings(blockList);
        }

        RunInstancesResult res = ec2.runInstances(req);

        try {
            Thread.sleep(60000);
        } catch (Exception e) {
        }
        for (Instance instance : res.getReservation().getInstances()) {
            createTags(instance.getInstanceId());
        }
    }

    // get number of on-demand instances that are not yet terminated
    public int getNumberOfCurrentOnDemandInstances() {
        int result = 0;
        // Use the tags on the instance to identify them.
        DescribeInstancesRequest req = new DescribeInstancesRequest()
                .withFilters(
                        new Filter().withName("tag:S3Bucket").withValues(cmdArgs.getS3Bucket()),
                        new Filter().withName("tag:IncomingQueue").withValues(cmdArgs.getIncoming_url()),
                        new Filter().withName("tag:OutgoingQueue").withValues(cmdArgs.getOutgoing_url())
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

    // get number of spot instances that are not yet terminated
   public int getNumberOfCurrentSpotInstances() {
        //New spot instances count
        int result = 0;
        DescribeInstancesRequest req = null;
        try {
        req = new DescribeInstancesRequest()
              .withFilters(
                     new Filter().withName("tag:S3Bucket").withValues(cmdArgs.getS3Bucket()),
                     new Filter().withName("tag:IncomingQueue").withValues(cmdArgs.getIncoming_url()),
                     new Filter().withName("tag:OutgoingQueue").withValues(cmdArgs.getOutgoing_url())
              );
        } catch (Exception ex) {
          System.out.println("Cannot filter spot instances");
        }

        DescribeInstancesResult res = ec2.describeInstances(req);
        for (Reservation r : res.getReservations()) {
            for (Instance i : r.getInstances()) {
                if (!i.getState().getName().equals("terminated") && i.getInstanceLifecycle() != null && i.getInstanceLifecycle().equals("spot") == true) {
                   result++;
                }
            }
        }
      return result;
    }

    String getUserData() {
        return Base64.encodeBase64String(
                String.format("WORKERARGS=\"--bucket %s --incoming %s --outgoing %s\"\nKEYSERVICE=\"%s\"",
                        cmdArgs.getS3Bucket(),
                        cmdArgs.getIncoming_url(),
                        cmdArgs.getOutgoing_url(),
                        cmdArgs.getServiceUri()
                ).getBytes()
        );
    }

    public CommandLineArguments getCmdArgs() {
        return cmdArgs;
    }

    public void setCmdArgs(CommandLineArguments cmdArgs) {
        this.cmdArgs = cmdArgs;
    }

    public void createTags(String id) {
        CreateTagsRequest createTagsRequest = new CreateTagsRequest();
        createTagsRequest.withResources(id)
                .withTags(new Tag("Name", String.format("Worker [%s]", cmdArgs.getS3Bucket())))
                .withTags(new Tag("S3Bucket", cmdArgs.getS3Bucket()))
                .withTags(new Tag("IncomingQueue", cmdArgs.getIncoming_url()))
                .withTags(new Tag("OutgoingQueue", cmdArgs.getOutgoing_url()))
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
}
