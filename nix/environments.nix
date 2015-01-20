rec {
  prod =
    { hostName = "steve.logicblox.com";
      elasticIPv4 = "54.243.141.142";
      workers = {
        "c3.xlarge" = { number = 1; price = "0.25"; };
        "r3.xlarge" = { number = 0; price = "0.40"; };
        "r3.2xlarge" = { number = 0; price = "0.75"; };
        "r3.8xlarge" = { number = 0; price = "3.00"; };
      };
    };

  test =
    { hostName = "steve-test.logicblox.com";
      elasticIPv4 = "23.21.124.192";
      inherit (prod) workers;
    };

  thiago =
    { hostName = "steve-thiago.logicblox.com";
      elasticIPv4 = "54.163.249.223";
      workers = { "c3.xlarge" = { number = 1; price = "0.25"; }; };
    };

  martin =
    { hostName = "steve-martin.logicblox.com";
      elasticIPv4 = "54.163.249.223";
      workers = { "c3.xlarge" = { number = 1; price = "0.25"; }; };
    };

}
