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
      workers = addOnDemandQueues {
        "c3.xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = true; };
        "n1-standard-2" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = false; instanceType = "n1-standard-2"; backend ="gcp"; ami = "lb-jobs-4665266"; defaultRegion = "us-central1-f"; project = "infor-faroi-dev";};
        "c3.xlarge-online" = { number = 1; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; };
        "c3.2xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; max = "500"; maxDelta = "100"; percentageQueue = "1.0"; };
        "c3.4xlarge" = { number = 0; price = "0.84"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "75"; maxDelta = "100"; };
        "hi1.4xlarge" = { number = 0; price = "3.3"; percentageSpot = "1.0"; max = "100"; percentageQueue = "1.0"; min = "50"; defaultRegion = "us-west-2"; };
        "r3.xlarge" = { number = 0; price = "0.40"; percentageSpot = "1.0"; max = "500"; };
        "r3.2xlarge" = { number = 0; price = "0.75"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; onDemand = true; maxDelta = "100"; };
        "r3.4xlarge" = { number = 0; price = "1.5"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; };
        "r3.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "50"; max = "500"; onDemand = true; maxDelta = "100"; };
        "i2.xlarge" = { number = 0; price = "0.86"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
        "i2.2xlarge" = { number = 0; price = "1.88"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; min = "75"; onDemand = true; maxDelta = "100";};
        "i2.4xlarge" = { number = 0; price = "3.72"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "300"; min = "75"; };
        "i2.8xlarge" = { number = 0; price = "7.44"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "50"; };
        "i3.xlarge" = { number = 0; price = "0.312"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
      };
    };

  test =
    { hostName = "steve-test.logicblox.com";
      elasticIPv4 = "23.21.124.192";
      inherit (prod) workers;
    };

  dev =
    { hostName = "steve-dev.logicblox.com";
      elasticIPv4 = "54.163.249.223";
      inherit (prod) workers;
    };

  dev-2 =
    { hostName = "steve-dev-2.logicblox.com";
      elasticIPv4 = "34.231.25.40";
      inherit (prod) workers;
    };

  integration =
    { hostName = "steve-integration.logicblox.com";
      elasticIPv4 = "18.233.197.187";
      inherit (prod) workers;
    };

  gcp-support =
    { hostName = "steve-gcp-support.logicblox.com";
      inherit (prod) workers;
    };
}
