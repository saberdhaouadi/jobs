let
  lib = (import <nixpkgs> {}).lib;

  addOnDemandQueues = queues:
    ( queues // lib.mapAttrs'
      (n: v: lib.nameValuePair "${n}-ondemand" ( v // { percentageSpot = "0"; instanceType = n; } ))
      (lib.filterAttrs (n: v: (v.onDemand or false)) queues)
    );

  subnetId = "subnet-dc1a4194";
  securityGroup = "sg-b3ac49c3";
in
rec {
  prod =
    { hostName = "steve.logicblox.com";
      elasticIPv4 = "54.243.141.142";
      key-server-elastic-ip = "";
      google-nat-elastic-ip = "nat-eip";
      workers = addOnDemandQueues {
        "c3.xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = true; };
        "n1-standard-2" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = false; instanceType = "n1-standard-2"; backend ="gcp"; ami = "lb-jobs-3174373"; defaultRegion = "us-central1-f"; project = "manifest-canto-796";};
        #"c3.xlarge-online" = { number = 1; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; };
        "c3.2xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; max = "500"; maxDelta = "100"; percentageQueue = "1.0"; };
        "c3.4xlarge" = { number = 0; price = "0.84"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "75"; maxDelta = "100"; };
        "r3.xlarge" = { number = 0; price = "0.40"; percentageSpot = "1.0"; max = "500"; };
        "r3.2xlarge" = { number = 0; price = "0.75"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; onDemand = true; maxDelta = "100"; };
        "r3.4xlarge" = { number = 0; price = "1.5"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; };
        "r3.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "50"; max = "500"; onDemand = true; maxDelta = "100"; };
        "i2.xlarge" = { number = 0; price = "0.86"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
        "i2.2xlarge" = { number = 0; price = "1.88"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; min = "75"; onDemand = true; maxDelta = "100";};
        "i2.4xlarge" = { number = 0; price = "3.72"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "300"; min = "75"; };
        "i2.8xlarge" = { number = 0; price = "7.44"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "50"; };
        "i3.8xlarge" = { number = 0; price = "2.50"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10";};
        "i3.4xlarge" = { number = 0; price = "1.25"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10";};
        "i3.2xlarge" = { number = 0; price = "0.625"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10";};
        "i3.xlarge" = { number = 0; price = "0.312"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10";};
      };
     region = {
          "us-east-1" = { securityGroupsIDs = ["sg-0c2e6ec0b43370a41"]; Subnets = ["subnet-08b8db33704bbbb8b" "subnet-063462c1d875f2d0f" "subnet-0a827b78d9497e579" "subnet-0d0c56d1c1439c527" "subnet-0d84a23d487ca13e9" "subnet-0465f94bf0b58b02d"]; s3-amis = "ami-42cacc38"; ebs-amis = "ami-6dc1c717";};
          "us-east-2" = { securityGroupsIDs = ["sg-05cbec8d1f38ef449"]; Subnets = ["subnet-a61d13de" "subnet-a78a7ece" "subnet-e7250bad"]; s3-amis = "ami-042fe64f79c92036d"; ebs-amis = "ami-0e7ec0b87551a4b73"; }; 
          "us-west-1" = { securityGroupsIDs = []; Subnets = []; s3-amis = "ami-ee545b8e"; ebs-amis = "ami-fb56599b";};
          "us-west-2" = { securityGroupsIDs = ["sg-06cc07f1e42fb423e"]; Subnets = ["subnet-0bca92cc49a00de8f" "subnet-09902f91c13df8ea3" "subnet-022f7226559497063" "subnet-0382caf37bde1ca84"]; s3-amis = "ami-ab1ea5d3"; ebs-amis = "ami-681ea510";};
          "us-central1-f" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = "";};
        };
     spotfleetRole = "arn:aws:iam::826045886586:role/aws-ec2-spot-fleet-role";
    };

  test =
    { hostName = "steve-test.logicblox.com";
      elasticIPv4 = "23.21.124.192";
      key-server-elastic-ip = "";
      inherit (prod) google-nat-elastic-ip;
      inherit (prod) workers;
      inherit (prod) spotfleetRole;
      inherit (prod) region;
    };

  dev =
    { hostName = "steve-dev.logicblox.com";
      #elasticIPv4 = "54.163.249.223";
      elasticIPv4 = "3.226.198.225";
      key-server-elastic-ip = "34.232.108.153";
      inherit (prod) google-nat-elastic-ip;
      inherit (prod) workers;
      spotfleetRole = "arn:aws:iam::202226491534:role/aws-ec2-spot-fleet-role";
      region = {
          "us-east-1" = { securityGroupsIDs = ["sg-4daea13f"]; Subnets = ["subnet-7451b329" "subnet-883299c3" "subnet-99ce6ea6" "subnet-b850b997" ]; s3-amis = "ami-01bd7cd6594a1816f"; ebs-amis = "ami-0142865c78c1ca071";};
          "us-east-2" = { securityGroupsIDs = ["sg-c6cbb6ae"]; Subnets = ["subnet-ce3b63b5" "subnet-23c4df4a" "subnet-f01983bd"]; s3-amis = "ami-0144a27be0cbec24f"; ebs-amis = "ami-05f8c29b555607ada"; };
          "us-west-1" = { securityGroupsIDs = ["sg-80237be6"]; Subnets = ["subnet-2e933749" "subnet-8639d3dd"]; s3-amis = "ami-02adf6c0efd853c18"; ebs-amis = "ami-07911b546dca4dc7e";};
          "us-west-2" = { securityGroupsIDs = ["sg-13527a6e"]; Subnets = ["subnet-406f6268" "subnet-7424102f" "subnet-95149cdd" "subnet-fc43289a"]; s3-amis = "ami-009e51f24ba468f53"; ebs-amis = "ami-098929c8e459cf9e5";};
          "us-central1-f" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = "";};
       };

    };

  dev-2 =
    { hostName = "steve-dev-2.logicblox.com";
      elasticIPv4 = "34.231.25.40";
      key-server-elastic-ip = "";
      inherit (prod) google-nat-elastic-ip;
      inherit (prod) workers;
      spotfleetRole = "";
      region = {  
          "us-east-1" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = "";};
          "us-east-2" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = ""; };
          "us-west-1" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = "";};
          "us-west-2" = { securityGroupsIDs = []; Subnets = []; s3-amis = ""; ebs-amis = "";};
       };

    };

  integration =
    { hostName = "steve-integration.logicblox.com";
      elasticIPv4 = "18.233.197.187";
      key-server-elastic-ip = "";
      inherit (prod) google-nat-elastic-ip;
      inherit (prod) workers;
      inherit (prod) spotfleetRole;
      inherit (prod) region;
    };

asamtitraining=
    { hostName = "steve-asamtitraining.logicblox.com";
      elasticIPv4 = "52.15.255.93";
      key-server-elastic-ip = "3.13.42.130";
      inherit (prod) google-nat-elastic-ip;
      inherit (prod) workers;
      inherit (prod) spotfleetRole;
      inherit (prod) region;
    };
}
