package com.logicblox.steve.provision;

import java.util.Arrays;
import java.util.List;

public class CommandLineArguments {

    private static String queue = "c3-xlarge";
    private static String incoming_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs";
    private static String outgoing_url = "https://sqs.us-east-1.amazonaws.com/297794765570/steve-jobs-results";
    private static String ami = "ami-820c2af9";
    private static String key = "rob";
    private static String region = "us-east-1";
    private static String s3Bucket = "steve-jobs";
    private static String instanceType = "c3.xlarge";
    private static String role = "steve-jobs-worker";
    private static String serviceUri = "http://localhost:8082/keys";
    private static String subnetId = null;
    private static String securityGroup = "admin";

    private static List<String> attrs = Arrays.asList("ApproximateNumberOfMessages", "ApproximateNumberOfMessagesNotVisible");
    private static double pctSpot = 0.9;
    private static double pctQueue = 0.6;
    private static double spotPrice = 0.6;
    private static int totalNeeded = 0;
    private static int maxDelta = -1;
    private static int maxInstances = 300;
    private static int minInstances = 0;
    private static boolean dryRun = true;
    private static int diskSize = 0;

    public static String getQueue() {
        return queue;
    }

    public static void setQueue(String queue) {
        CommandLineArguments.queue = queue;
    }

    public static String getIncoming_url() {
        return incoming_url;
    }

    public static void setIncoming_url(String incoming_url) {
        CommandLineArguments.incoming_url = incoming_url;
    }

    public static String getOutgoing_url() {
        return outgoing_url;
    }

    public static void setOutgoing_url(String outgoing_url) {
        CommandLineArguments.outgoing_url = outgoing_url;
    }

    public static String getAmi() {
        return ami;
    }

    public static void setAmi(String ami) {
        CommandLineArguments.ami = ami;
    }

    public static String getKey() {
        return key;
    }

    public static void setKey(String key) {
        CommandLineArguments.key = key;
    }

    public static String getRegion() {
        return region;
    }

    public static void setRegion(String region) {
        CommandLineArguments.region = region;
    }

    public static String getS3Bucket() {
        return s3Bucket;
    }

    public static void setS3Bucket(String s3Bucket) {
        CommandLineArguments.s3Bucket = s3Bucket;
    }

    public static String getInstanceType() {
        return instanceType;
    }

    public static void setInstanceType(String instanceType) {
        CommandLineArguments.instanceType = instanceType;
    }

    public static String getRole() {
        return role;
    }

    public static void setRole(String role) {
        CommandLineArguments.role = role;
    }

    public static String getServiceUri() {
        return serviceUri;
    }

    public static void setServiceUri(String serviceUri) {
        CommandLineArguments.serviceUri = serviceUri;
    }

    public static String getSubnetId() {
        return subnetId;
    }

    public static void setSubnetId(String subnetId) {
        CommandLineArguments.subnetId = subnetId;
    }

    public static String getSecurityGroup() {
        return securityGroup;
    }

    public static void setSecurityGroup(String securityGroup) {
        CommandLineArguments.securityGroup = securityGroup;
    }

    public static List<String> getAttrs() {
        return attrs;
    }

    public static void setAttrs(List<String> attrs) {
        CommandLineArguments.attrs = attrs;
    }

    public static double getPctSpot() {
        return pctSpot;
    }

    public static void setPctSpot(double pctSpot) {
        CommandLineArguments.pctSpot = pctSpot;
    }

    public static double getPctQueue() {
        return pctQueue;
    }

    public static void setPctQueue(double pctQueue) {
        CommandLineArguments.pctQueue = pctQueue;
    }

    public static double getSpotPrice() {
        return spotPrice;
    }

    public static void setSpotPrice(double spotPrice) {
        CommandLineArguments.spotPrice = spotPrice;
    }

    public static int getTotalNeeded() {
        return totalNeeded;
    }

    public static void setTotalNeeded(int totalNeeded) {
        CommandLineArguments.totalNeeded = totalNeeded;
    }

    public static int getMaxDelta() {
        return maxDelta;
    }

    public static void setMaxDelta(int maxDelta) {
        CommandLineArguments.maxDelta = maxDelta;
    }

    public static int getMaxInstances() {
        return maxInstances;
    }

    public static void setMaxInstances(int maxInstances) {
        CommandLineArguments.maxInstances = maxInstances;
    }

    public static int getMinInstances() {
        return minInstances;
    }

    public static void setMinInstances(int minInstances) {
        CommandLineArguments.minInstances = minInstances;
    }

    public static boolean isDryRun() {
        return dryRun;
    }

    public static void setDryRun(boolean dryRun) {
        CommandLineArguments.dryRun = dryRun;
    }

    public static int getDiskSize() {
        return diskSize;
    }

    public static void setDiskSize(int diskSize) {
        CommandLineArguments.diskSize = diskSize;
    }

}
