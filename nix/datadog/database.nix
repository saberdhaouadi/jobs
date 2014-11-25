{ config, pkgs, lib, ... }:
{
  systemd.services.dd-agent.environment.PYTHONPATH = "${pkgs.pythonPackages.requests}/lib/python2.7/site-packages";
  environment.etc =
    let
      lb-steve-database-config =
        pkgs.writeText "lb-steve-database.yaml" ''
          init_config:

          instances:
            [{}]
        '';
    in [
      { source = lb-steve-database-config;
        target = "dd-agent/conf.d/lb-steve-database.yaml";
      }
      { source = ./lb-steve-database.py;
        target = "dd-agent/checks.d/lb-steve-database.py";
      }
    ];
}

