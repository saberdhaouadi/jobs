{ config, pkgs, lib, ... }:
{
  systemd.services.dd-agent.environment.PYTHONPATH = "${pkgs.pythonPackages.boto}/lib/python2.7/site-packages";
  environment.etc =
    let
      lb-jobs-provisioner-config =
        pkgs.writeText "lb-jobs-provisioner.yaml" ''
          init_config:

          instances:
            [{}]
        '';
    in [
      { source = lb-jobs-provisioner-config;
        target = "dd-agent/conf.d/lb-jobs-provisioner.yaml";
      }
      { source = ./lb-jobs-provisioner.py;
        target = "dd-agent/checks.d/lb-jobs-provisioner.py";
      }
    ];
}

