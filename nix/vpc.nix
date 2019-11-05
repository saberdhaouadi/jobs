# Subnets config is replicated from the lb-jobs-dev-2
# setup, some AZs are removed due to issues while provisioning
# spot instances ? (so the list needs to be updated)
{ name
, region ? "us-east-1"
, account
, ...
}:
let
  overrideVpc =
    { lib, resources, ... }:
    {
      vpcId = lib.mkForce resources.vpc.lb-jobs-vpc;
    };
  overrideEc2Vpc = type:
    { lib, resources, ... }:
    {
      deployment.ec2 = {
        instanceType = lib.mkForce type;
        subnetId = resources.vpcSubnets.subnet-a;
        securityGroups = lib.mkForce [];
        securityGroupIds = [ resources.ec2SecurityGroups.admin.name resources.ec2SecurityGroups.frontend-sg.name ];
        associatePublicIpAddress = true;
      };
    };

  accessKeyId = account;
in
with (import <nixpkgs/lib>);
{
  resources.vpc.lb-jobs-vpc =
    {
      inherit region accessKeyId;
      instanceTenancy = "default";
      enableDnsSupport = true;
      enableDnsHostnames = true;
      cidrBlock = "172.31.0.0/16";
    };

  resources.vpcSubnets =
    let
      subnet = cidr: zone:
        { resources, ... }:
        {
          inherit region zone accessKeyId;
          vpcId = resources.vpc.lb-jobs-vpc;
          cidrBlock = cidr;
          mapPublicIpOnLaunch = true;
          tags = {
            Source = "NixOps";
          };
        };
    in
    {
      nat-subnet = subnet "172.31.96.0/28" "us-east-1a";
      subnet-a = subnet "172.31.80.0/20" "us-east-1a";
      subnet-b = subnet "172.31.16.0/20" "us-east-1b";
      subnet-c = subnet "172.31.32.0/20" "us-east-1c";
      subnet-e = subnet "172.31.64.0/20" "us-east-1e";
    };

  resources.vpcRouteTables =
    let
      rtb =
        { resources, ... }:
        {
          inherit region accessKeyId;
          vpcId = resources.vpc.lb-jobs-vpc;
        };
    in
    {
      route-table = rtb;
      nat-route-table = rtb;
    };

  resources.vpcRouteTableAssociations =
    let
      association = subnet:
        { resources, ... }:
        {
          inherit region accessKeyId;
          subnetId = resources.vpcSubnets."${subnet}";
          routeTableId = resources.vpcRouteTables.route-table;
        };
    in
    (builtins.listToAttrs
      (map
        (s: nameValuePair "association-${s}" (association s))
        [ "subnet-a" "subnet-b" "subnet-c" "subnet-e" ]
      )
    );

  resources.elasticIPs.nat-eip =
    {
      inherit region accessKeyId;
      vpc = true;
    };

  resources.elasticIPs.frontend-eip =
    {
      inherit region accessKeyId;
      vpc = true;
    };

  resources.vpcNatGateways.nat =
    { resources, ... }:
    {
      inherit region accessKeyId;
      allocationId = resources.elasticIPs.nat-eip;
      subnetId = resources.vpcSubnets.nat-subnet;
    };

  resources.vpcInternetGateways.igw =
    { resources, ... }:
    {
      inherit region accessKeyId;
      vpcId = resources.vpc.lb-jobs-vpc;
    };

  resources.vpcRoutes =
    let
      routeName = cidr: builtins.replaceStrings [ "." "/" ] [ "_" "_" ] cidr;
      natRoute = destinationCidrBlock:
        { resources, ... }:
        {
          inherit region accessKeyId destinationCidrBlock;
          routeTableId = resources.vpcRouteTables.route-table;
          natGatewayId = resources.vpcNatGateways.nat;
        };
    in
    {
      igw-route =
        { resources, ... }:
        {
          inherit region accessKeyId;
          routeTableId = resources.vpcRouteTables.route-table;
          destinationCidrBlock = "0.0.0.0/0";
          gatewayId = resources.vpcInternetGateways.igw;
        };
      igw-nat-route =
        { resources, ... }:
        {
          inherit region accessKeyId;
          routeTableId = resources.vpcRouteTables.nat-route-table;
          destinationCidrBlock = "0.0.0.0/0";
          gatewayId = resources.vpcInternetGateways.igw;
        };

    } //
    (builtins.listToAttrs
      (map (d: nameValuePair "route-${routeName d}" (natRoute d) )
      # NOTE: document each IP
      [ "3.16.16.239/32"
        "3.16.93.144/32"
        "3.16.94.192/32"
        "23.21.124.192/32"
        "34.195.151.111/32"
        "34.195.165.8/32"
        "34.231.25.40/32"
        "54.83.193.103/32"
        "54.163.249.223/32"
        "54.235.119.239/32"
        "54.243.141.142/32"
      ])
    );

  resources.ec2SecurityGroups = {
    frontend-sg = overrideVpc;
    admin = let
      rule = port: ip:
        { fromPort = port;
          toPort = port;
          sourceIp = "${ip}/32";
        };
      ruleFromRes = port: ip:
      { fromPort = port;
        toPort = port;
        sourceIp = ip;
      };

      httpRule = ip: rule 80 ip;
      sshRule = ip: rule 22 ip;
      httpsRule = ip: rule 443 ip;
      icmpRule = ip: rule -1 ip;
      httpIps = [
        "38.104.0.30"
        "208.92.248.6"
        "38.101.227.60"
        "196.203.15.128"
        "54.236.204.123"
        "54.156.0.100"
        "213.241.96.138"
        "196.179.235.56"
      ];
      sshIps = [
        "54.156.0.102" # Hades
      ];
    in
    { resources, ... }:
    {
      inherit region accessKeyId;
      vpcId = resources.vpc.lb-jobs-vpc;
      description = "Admin Security Group";
      rules = (map httpRule httpIps) ++ (map httpsRule httpIps) ++ (map sshRule sshIps);
    };
  };
  "provisioner-${name}" = overrideEc2Vpc "r4.xlarge";
  "key-server-${name}" = overrideEc2Vpc "r4.xlarge";
  "database-${name}" = overrideEc2Vpc "c4.8xlarge";
  "steve-${name}" =
     { lib, resources, ... }:
     { imports = [ (overrideEc2Vpc "c4.xlarge") ];
       deployment.ec2.elasticIPv4 = lib.mkForce resources.elasticIPs.frontend-eip;
     };
}
