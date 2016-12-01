let
  lib = (import <nixpkgs> {}).lib;

  addOnDemandQueues = queues:
    ( queues // lib.mapAttrs'
      (n: v: lib.nameValuePair "${n}-ondemand" ( v // { percentageSpot = "0"; instanceType = n; } ))
      (lib.filterAttrs (n: v: (v.onDemand or false)) queues)
    );

in
rec {
  prod =
    { hostName = "steve.logicblox.com";
      elasticIPv4 = "54.243.141.142";
      workers = addOnDemandQueues {
        "c3.xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; onDemand = true; };
        "c3.xlarge-online" = { number = 2; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; };
        "c3.2xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; max = "100"; };
        "c3.4xlarge" = { number = 0; price = "0.84"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "75"; };
        "hi1.4xlarge" = { number = 0; price = "3.3"; percentageSpot = "1.0"; max = "100"; percentageQueue = "1.0"; min = "50"; };
        "r3.xlarge" = { number = 0; price = "0.40"; percentageSpot = "1.0"; max = "500"; };
        "r3.2xlarge" = { number = 0; price = "0.75"; percentageSpot = "1.0"; max = "500"; onDemand = true; };
        "r3.4xlarge" = { number = 0; price = "1.5"; percentageSpot = "1.0"; max = "200"; };
        "r3.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; percentageQueue = "1.0"; min = "50"; onDemand = true; };
        "i2.xlarge" = { number = 0; price = "0.86"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
        "i2.2xlarge" = { number = 0; price = "1.88"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "300"; min = "75"; onDemand = true; };
        "i2.4xlarge" = { number = 0; price = "3.72"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "100"; min = "75"; };
        "i2.8xlarge" = { number = 0; price = "7.44"; percentageSpot = "1.0"; percentageQueue = "1.0"; max = "50"; };
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
