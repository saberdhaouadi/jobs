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
      workers = addOnDemandQueues {
        "i2.xlarge" = { number = 0; price = "0.86"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
        "i2.2xlarge" = { number = 0; price = "1.88"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; min = "75"; onDemand = true; maxDelta = "100";};
        "i2.4xlarge" = { number = 0; price = "3.72"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "300"; min = "75"; };
        "i2.8xlarge" = { number = 0; price = "7.44"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "50"; };
        "i3.xlarge" = { number = 0; price = "0.312"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
      };
      region = {
          "us-east-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = "ami-42cacc38"; ebs-amis="ami-6dc1c717";};
          "us-east-2" = { securityGroupsIDs = ["sg-05cbec8d1f38ef449"]; Subnets =["subnet-a61d13de" "subnet-a78a7ece" "subnet-e7250bad"]; s3-amis = "ami-042fe64f79c92036d"; ebs-amis="ami-0e7ec0b87551a4b73"; }; 
          "us-west-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = "ami-ee545b8e"; ebs-amis="ami-fb56599b";};
          "us-west-2" = { securityGroupsIDs = ["sg-06cc07f1e42fb423e"]; Subnets =["subnet-0bca92cc49a00de8f" "subnet-09902f91c13df8ea3" "subnet-022f7226559497063" "subnet-0382caf37bde1ca84"]; s3-amis = "ami-ab1ea5d3"; ebs-amis="ami-681ea510";};
        };

    };

  test =
    { hostName = "steve-test.logicblox.com";
      elasticIPv4 = "23.21.124.192";
      key-server-elastic-ip = "";
      inherit (prod) workers;
      inherit (prod) region;
    };

  dev =
    { hostName = "steve-dev.logicblox.com";
      elasticIPv4 = "54.163.249.223";
      key-server-elastic-ip = "";
      inherit (prod) workers;
      region = {
          "us-east-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
          "us-east-2" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis=""; }; 
          "us-west-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
          "us-west-2" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
        };
    };

  dev-2 =
    { hostName = "steve-dev-2.logicblox.com";
      elasticIPv4 = "34.231.25.40";
      key-server-elastic-ip = "";
      inherit (prod) workers;
      region = {
         "us-east-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
         "us-east-2" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis=""; };                                               
         "us-west-1" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
         "us-west-2" = { securityGroupsIDs = []; Subnets =[]; s3-amis = ""; ebs-amis="";};
        };

    };

  integration =
    { hostName = "steve-integration.logicblox.com";
      elasticIPv4 = "18.233.197.187";
      key-server-elastic-ip = "";
      inherit (prod) workers;
      inherit (prod) region;
    };
  asamtitraining=
    { hostName = "steve-asamtitraining.logicblox.com";
      elasticIPv4 = "52.15.255.93";
      key-server-elastic-ip = "3.13.42.130";
      inherit (prod) workers;
      inherit (prod) region;
    };
}
