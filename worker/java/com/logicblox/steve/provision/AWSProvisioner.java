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
        /*System.err.println(String.format("Creating %d spot instances", nr));

        if (cmdArgs.isDryRun())
            return;

        RequestSpotInstancesRequest req = new RequestSpotInstancesRequest();
        req.setInstanceCount(nr);
        req.setSpotPrice(Double.toString(cmdArgs.getSpotPrice()));
        LaunchSpecification spec = new LaunchSpecification();
        spec.setImageId(cmdArgs.getAmi());
        spec.setInstanceType(cmdArgs.getInstanceType());
        spec.setIamInstanceProfile(new IamInstanceProfileSpecification().withName(cmdArgs.getRole()));
        spec.setKeyName(cmdArgs.getKey());
        spec.setUserData(getUserData());

        if(cmdArgs.getSubnetId() == null) {
            Collection<String> groups = new ArrayList<String>();
            groups.add(cmdArgs.getSecurityGroup());
            spec.setSecurityGroups(groups);
        } else {
            Collection<GroupIdentifier> groups = new ArrayList<GroupIdentifier>();
            groups.add(new GroupIdentifier().withGroupId(cmdArgs.getSecurityGroup()));
            spec.setAllSecurityGroups(groups);
        }

        if(cmdArgs.getSubnetId() != null) {
            spec.setSubnetId(cmdArgs.getSubnetId());
        }
        if(cmdArgs.getDiskSize() != 0) {
            BlockDeviceMapping blockDeviceMapping = new BlockDeviceMapping();
            blockDeviceMapping.setDeviceName("/dev/sda1");

            EbsBlockDevice ebs = new EbsBlockDevice();
            ebs.setVolumeSize(cmdArgs.getDiskSize());
            blockDeviceMapping.setEbs(ebs);

            ArrayList<BlockDeviceMapping> blockList = new ArrayList<BlockDeviceMapping>();
            blockList.add(blockDeviceMapping);

            spec.setBlockDeviceMappings(blockList);
        }

        req.setLaunchSpecification(spec);

        RequestSpotInstancesResult res = ec2.requestSpotInstances(req);
        try {
            Thread.sleep(60000);
        } catch (Exception e) {
        }
        for (SpotInstanceRequest sir : res.getSpotInstanceRequests()) {
            createTags(sir.getSpotInstanceRequestId());
        }*/
       
        
        //*************** Spot fleet *******************

        System.err.println(String.format("Creating %d spot instances", nr));

        List<String> SubnetsList = Arrays.asList(cmdArgs.getSubnets().split("\\s*/\\s*"));
        Collection<Tag> tags = new ArrayList<Tag>();
        Collection<SpotFleetTagSpecification> tagspeclist = new ArrayList<SpotFleetTagSpecification>();
        //Collection<groupidentifier> identgroups = new ArrayList<groupidentifier>();
        tags.add(new Tag("Name", String.format("Worker [%s]", cmdArgs.getS3Bucket())));
        tags.add(new Tag("S3Bucket", cmdArgs.getS3Bucket()));
        tags.add(new Tag("IncomingQueue", cmdArgs.getIncoming_url()));
        tags.add(new Tag("OutgoingQueue", cmdArgs.getOutgoing_url()));

        System.out.println(SubnetsList);

        if (cmdArgs.isDryRun())
        return;

        RequestSpotFleetRequest request = new RequestSpotFleetRequest();

        SpotFleetRequestConfigData fleetconfig = new SpotFleetRequestConfigData();

        //fix spot fleet role argument
        fleetconfig.setIamFleetRole(cmdArgs.getSpotFleetRole());
        fleetconfig.setSpotPrice(Double.toString(cmdArgs.getSpotPrice()));
        fleetconfig.setTargetCapacity(nr);
        fleetconfig.setType("request");

        fleetconfig.setAllocationStrategy("diversified");
        //fleetconfig.setAllocationStrategy("capacityOptimized");    
        //fleetconfig.setAllocationStrategy("lowestPrice");
        //fleetconfig.setInstancePoolsToUseCount(2);

        Collection<SpotFleetLaunchSpecification> LaunchSpecs = new ArrayList<SpotFleetLaunchSpecification>();
       
        GroupIdentifier groupidf = new GroupIdentifier();
        groupidf.setGroupId(cmdArgs.getSecGrpId());

        Collection<GroupIdentifier> identgroups = new ArrayList<GroupIdentifier>();
        identgroups.add(groupidf);
        

        SpotFleetTagSpecification fleettagsspec = new SpotFleetTagSpecification();
        fleettagsspec.setTags(tags);
        fleettagsspec.setResourceType("instance");
        tagspeclist.add(fleettagsspec);       

        for (String sp : SubnetsList)

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

       //int result = 0;

        try {

        Thread.sleep(10000);

        } catch (Exception e) {

        }

        System.out.println(String.format("Spot fleet request ID %s",fleetID));

    
    //********************EC2fleet code*************************
  
        /*CreateFleetRequest fleetreq = new CreateFleetRequest();

       //capacity
       TargetCapacitySpecificationRequest targetcapacity = new TargetCapacitySpecificationRequest();
       targetcapacity.setDefaultTargetCapacityType("spot");
       targetcapacity.setTotalTargetCapacity(nr);
       fleetreq.setTargetCapacitySpecification(targetcapacity);

       //requestType
       fleetreq.setType("request");

       fleetreq.setTerminateInstancesWithExpiration(true);

       //spot config
       SpotOptionsRequest spotopt = new SpotOptionsRequest() ;
       spotopt.setAllocationStrategy("diversified");
       //spotopt.setAllocationStrategy("lowestPrice");
       //spotopt.setInstancePoolsToUseCount(3);

       fleetreq.setSpotOptions(spotopt);

       //tagging
       Collection<TagSpecification> tagSpecifications = new ArrayList<TagSpecification>();
       TagSpecification tagspec = new TagSpecification();
       tagspec.setResourceType("instance");
       tagspec.setTags(tags);
       tagSpecifications.add(tagspec);
       fleetreq.setTagSpecifications(tagSpecifications);

       //launch template
       Collection<FleetLaunchTemplateConfigRequest> fleetlaunchConfReqs = new ArrayList<FleetLaunchTemplateConfigRequest>();

       FleetLaunchTemplateConfigRequest fleettempconf = new FleetLaunchTemplateConfigRequest() ;

       FleetLaunchTemplateSpecificationRequest launchTempSpec = new FleetLaunchTemplateSpecificationRequest();

       launchTempSpec.setLaunchTemplateId("lt-059e1e3a4dc07d519");
       //launchTempSpec.setVersion(1);

       fleettempconf.setLaunchTemplateSpecification(launchTempSpec);

       //launch template overrides
       Collection<FleetLaunchTemplateOverridesRequest> tempoverrides = new ArrayList<FleetLaunchTemplateOverridesRequest>();
       for (String sb : SubnetsList)
       {     
        FleetLaunchTemplateOverridesRequest launchoverride = new FleetLaunchTemplateOverridesRequest();
        launchoverride.setInstanceType(cmdArgs.getInstanceType());
        launchoverride.setSubnetId(sb);
        launchoverride.setMaxPrice(Double.toString(cmdArgs.getSpotPrice()));

        tempoverrides.add(launchoverride); 
    
       }
       fleettempconf.setOverrides(tempoverrides);
       fleetlaunchConfReqs.add(fleettempconf);
       fleetreq.setLaunchTemplateConfigs(fleetlaunchConfReqs);
       
       CreateFleetResult fleetresponse =ec2.createFleet(fleetreq);

       String fleetID = fleetresponse.getFleetId();

        try {
        Thread.sleep(30000);
      } catch (Exception e) {
      }
       System.out.println(String.format("EC2 fleet request ID %s",fleetID)); */ 
          
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

        if(cmdArgs.getSubnetId() == null) {
            Collection<String> groups = new ArrayList<String>();
            groups.add(cmdArgs.getSecurityGroup());
            req.setSecurityGroups(groups);
        } else {
            Collection<String> groups = new ArrayList<String>();
            groups.add(cmdArgs.getSecurityGroup());
            req.setSecurityGroupIds(groups);
        }

        if(cmdArgs.getSubnetId() != null) {
            req.setSubnetId(cmdArgs.getSubnetId());
        }
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
       /* int result = 0;

        for(Regions region: regions) {
            AmazonEC2Client _ec2 = new AmazonEC2Client();
            _ec2.setRegion(Region.getRegion(region));

            DescribeSpotInstanceRequestsRequest spreq = new DescribeSpotInstanceRequestsRequest()
                    .withFilters(
                            new Filter().withName("tag:S3Bucket").withValues(cmdArgs.getS3Bucket()),
                            new Filter().withName("tag:IncomingQueue").withValues(cmdArgs.getIncoming_url()),
                            new Filter().withName("tag:OutgoingQueue").withValues(cmdArgs.getOutgoing_url()),
                            new Filter().withName("state").withValues("open", "active")
                    );
            DescribeSpotInstanceRequestsResult spres = _ec2.describeSpotInstanceRequests(spreq);
            for (SpotInstanceRequest r : spres.getSpotInstanceRequests()) {
                result++;
            }
        }
        return result;*/
        /////new spot instances count
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
        if (!i.getState().getName().equals("terminated") && i.getInstanceLifecycle().equals("spot") == true) {
            result++;
          }
        }
      }
      return result;

        
    }

    String getUserData() {
        return Base64.encodeBase64String(
                String.format("WORKERARGS=\"--bucket %s --incoming %s --outgoing %s --key-service %s\"",
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
