let
  lib = (import <nixpkgs> {}).lib;

  addOnDemandQueues = queues:
    ( queues // lib.mapAttrs'
      (n: v: lib.nameValuePair "${n}-ondemand" ( v // { percentageSpot = "0"; instanceType = (v.instanceType or n); } ))
      (lib.filterAttrs (n: v: (v.onDemand or false)) queues)
    );
  
  subnetId = "subnet-062fba8acc7b8ed99";
  securityGroup = "sg-0763799058c74c1ec";

in
rec {
  prod =
    { hostName = "steve.logicblox.com";
      elasticIPv4 = "54.243.141.142";
      workers = addOnDemandQueues {
        "c3.xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = true; };
        "c3.xlarge-online" = { number = 1; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; };
        "c3.2xlarge" = { number = 0; price = "0.625"; percentageSpot = "1.0"; max = "600"; maxDelta = "100"; percentageQueue = "1.0"; instanceType = "i3.2xlarge"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; subnetId = "subnet-04b4129d5184e8a0f"; inherit securityGroup; };
        "c3.4xlarge" = { number = 0; price = "1.25"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "75"; maxDelta = "100"; instanceType = "i3.4xlarge"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; subnetId = "subnet-01c31086f64d5d173"; inherit securityGroup; };
        "r3.xlarge" = { number = 0; price = "0.40"; percentageSpot = "1.0"; max = "500"; };
        "r3.2xlarge" = { number = 0; price = "0.625"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "650"; onDemand = true; maxDelta = "100"; instanceType = "i3.2xlarge"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; subnetId = "subnet-04b4129d5184e8a0f"; inherit securityGroup; };
        "r3.4xlarge" = { number = 0; price = "1.5"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; maxDelta = "50"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; instanceType = "i3.4xlarge"; subnetId = "subnet-01c31086f64d5d173"; inherit securityGroup; };
        "r3.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "50"; max = "500"; onDemand = true; maxDelta = "50"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; instanceType = "i3.8xlarge"; subnetId = "subnet-062fba8acc7b8ed99";inherit securityGroup;};
        "i2.xlarge" = { number = 0; price = "0.86"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
        "i2.2xlarge" = { number = 0; price = "1.88"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "500"; min = "75"; onDemand = true; maxDelta = "100";};
        "i2.4xlarge" = { number = 0; price = "3.72"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "300"; min = "75"; };
        "i2.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "50"; keyService = "https://54.167.63.233/keys"; diskSize = "10"; instanceType = "i3.8xlarge"; subnetId = "subnet-062fba8acc7b8ed99"; inherit securityGroup; };
        "i3.8xlarge" = { number = 0; price = "2.50"; keyService = "https://54.167.63.233/keys"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
        "i3.4xlarge" = { number = 0; price = "1.25"; keyService = "https://54.167.63.233/keys"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
        "i3.2xlarge" = { number = 0; price = "0.625"; keyService = "https://54.167.63.233/keys"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
        "i3.xlarge" = { number = 0; price = "0.312"; keyService = "https://54.167.63.233/keys"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; diskSize = "10"; inherit subnetId securityGroup; };
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

}
