rec {
  prod =
    { hostName = "steve.logicblox.com";
      elasticIPv4 = "54.243.141.142";
      workers = {
        "c3.xlarge" = { number = 0; price = "0.25"; percentageSpot = "1.0"; };
        "c3.xlarge-online" = { number = 2; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; };
        "c3.4xlarge" = { number = 0; price = "0.84"; percentageSpot = "1.0"; min = "75"; };
        "hi1.4xlarge" = { number = 0; price = "3.3"; percentageSpot = "1.0"; max = "50"; percentageQueue = "1.0"; min = "50"; };
        "r3.xlarge" = { number = 0; price = "0.40"; percentageSpot = "1.0"; };
        "r3.2xlarge" = { number = 0; price = "0.75"; percentageSpot = "1.0"; };
        "r3.8xlarge" = { number = 0; price = "3.00"; percentageSpot = "1.0"; };
        "i2.2xlarge" = { number = 0; price = "3.00"; percentageSpot = "0"; percentageQueue = "1.0"; max = "200"; min = "75"; };
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
      workers = {
        "c3.xlarge-online" = { number = 1; price = "0.25"; percentageSpot = "0"; instanceType = "c3.xlarge"; max = "50";};
        inherit (prod.workers) "c3.xlarge";
      };
    };

  martin =
    { hostName = "steve-martin.logicblox.com";
      elasticIPv4 = "54.163.249.223";
      workers = { "c3.xlarge" = { number = 1; price = "0.25"; }; };
    };

}
