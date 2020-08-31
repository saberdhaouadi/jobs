let
  lib = (import <nixpkgs> { }).lib;

  addOnDemandQueues = queues:
    (queues // lib.mapAttrs' (n: v:
      lib.nameValuePair "${n}-ondemand" (v // {
        percentageSpot = "0";
        instanceType = n;
      })) (lib.filterAttrs (n: v: (v.onDemand or false)) queues));

  subnetId = "subnet-dc1a4194";
  securityGroup = "sg-b3ac49c3";
  lbJobsImage = "lb-jobs-5201168";
  gcpProject = "lb-jobs";
  gcpServiceAccount = "lb-jobs-dev@lb-jobs.iam.gserviceaccount.com";

in rec {
  prod = {
    hostName = "steve.logicblox.com";
    elasticIPv4 = "54.243.141.142";
    workers = addOnDemandQueues {
      "c3.xlarge" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        onDemand = true;
      };
      "c3.2xlarge" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        max = "500";
        maxDelta = "100";
        percentageQueue = "1.0";
      };
      "c3.4xlarge" = {
        number = 0;
        price = "0.84";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        min = "75";
        maxDelta = "100";
      };
      "hi1.4xlarge" = {
        number = 0;
        price = "3.3";
        percentageSpot = "1.0";
        max = "100";
        percentageQueue = "1.0";
        min = "50";
        defaultRegion = "us-west-2";
      };
      "r3.xlarge" = {
        number = 0;
        price = "0.40";
        percentageSpot = "1.0";
        max = "500";
      };
      "r3.2xlarge" = {
        number = 0;
        price = "0.75";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "700";
        onDemand = true;
        maxDelta = "100";
      };
      "r3.4xlarge" = {
        instanceType = "i3.4xlarge";
        diskSize = "10";
        number = 0;
        price = "1.5";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
      };
      "r3.8xlarge" = {
        instanceType = "i3.8xlarge";
        diskSize = "10";
        number = 0;
        price = "3.00";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        min = "50";
        max = "500";
        onDemand = true;
        maxDelta = "100";
      };
      "i2.xlarge" = {
        number = 0;
        price = "0.86";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
        min = "75";
      };
      "i2.2xlarge" = {
        number = 0;
        price = "1.88";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "500";
        min = "75";
        onDemand = true;
        maxDelta = "100";
      };
      "i2.4xlarge" = {
        number = 0;
        price = "3.72";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "300";
        min = "75";
      };
      "i2.8xlarge" = {
        instanceType = "i3.8xlarge";
        diskSize = "10";
        number = 0;
        price = "3.00";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "50";
      };
      "i3.xlarge" = {
        number = 0;
        price = "0.312";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
        min = "75";
        diskSize = "10";
      };
      "i3.2xlarge" = {
        number = 0;
        price = "0.625";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
        min = "75";
        diskSize = "10";
      };
      "i3.4xlarge" = {
        number = 0;
        price = "1.25";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
        min = "75";
        diskSize = "10";
      };
      "i3.8xlarge" = {
        number = 0;
        price = "2.50";
        percentageSpot = "1.0";
        percentageQueue = "1.0";
        max = "200";
        min = "75";
        diskSize = "10";
      };
      /** Google cloud queues
      * TODO:
      *   - add more instance types
      *   - replace references to infor-faroi-dev with an lb-jobs
      *      account (project/serviceAccount/ami)
      */
      "n1-standard-2" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        onDemand = false;
        instanceType = "n1-standard-2";
        backend = "gcp";
        ami = lbJobsImage;
        defaultRegion = "us-central1-f";
        project = gcpProject;
        serviceAccount = gcpServiceAccount;
      };

      "n1-highmem-8" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        onDemand = false;
        instanceType = "n1-highmem-8";
        backend = "gcp";
        ami = lbJobsImage;
        defaultRegion = "us-central1-f";
        project = gcpProject;
        serviceAccount = gcpServiceAccount;
      };

      "n1-highmem-32" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        onDemand = false;
        instanceType = "n1-highmem-32";
        backend = "gcp";
        ami = lbJobsImage;
        defaultRegion = "us-central1-f";
        project = gcpProject;
        serviceAccount = gcpServiceAccount;
      };

      "n2-highmem-32" = {
        number = 0;
        price = "0.25";
        percentageSpot = "1.0";
        onDemand = false;
        instanceType = "n2-highmem-32";
        backend = "gcp";
        ami = lbJobsImage;
        defaultRegion = "us-central1-f";
        project = gcpProject;
        serviceAccount = gcpServiceAccount;
      };

    };

    region = {
      "us-east-1" = {};
      "us-west-1" = {};
      "us-west-2" = {};
      "us-central1-f" = {};
    };
    spotfleetRole = "arn:aws:iam::826045886586:role/aws-ec2-spot-fleet-role";
  };

  test =
    { hostName = "steve-test.logicblox.com";
      elasticIPv4 = "23.21.124.192";
      key-server-elastic-ip = "";
      google-nat-elastic-ip = "";
      inherit (prod) workers;
      inherit (prod) spotfleetRole;
      inherit (prod) region;
    };

  dev =
    { hostName = "steve-dev.logicblox.com";
      elasticIPv4 = "3.226.198.225";
      key-server-elastic-ip = "34.232.108.153";
      google-nat-elastic-ip = "google-nat-dev";
      inherit (prod) workers;
      inherit (prod) region;
      spotfleetRole = "arn:aws:iam::202226491534:role/aws-ec2-spot-fleet-role";
    };

  dev-2 =
    { hostName = "steve-dev-2.logicblox.com";
      elasticIPv4 = "34.231.25.40";
      key-server-elastic-ip = "3.209.190.228";
      google-nat-elastic-ip = "google-nat";
      inherit (prod) workers;
      inherit (prod) region;
      inherit (dev) spotfleetRole;
    };

  integration =
    { hostName = "steve-integration.logicblox.com";
      elasticIPv4 = "18.233.197.187";
      key-server-elastic-ip = "";
      inherit (prod) workers;
      inherit (prod) region;
      inherit (prod) spotfleetRole;
    };

  asamtitraining=
    { hostName = "steve-asamtitraining.logicblox.com";
      elasticIPv4 = "52.15.255.93";
      key-server-elastic-ip = "3.13.42.130";
      google-nat-elastic-ip = "";
      inherit (prod) workers;
      inherit (prod) region;
      inherit (prod) spotfleetRole;
    };

}
