{ config, pkgs, lib, ... }:
let
  generic-config =
    pkgs.writeText "generic.yaml" ''
      init_config:

      instances:
        [{}]
    '';
  billing = import <global_creds/billing.nix>;
in
{
  systemd.services.dd-agent.environment = {
    PYTHONPATH = "${pkgs.pythonPackages.boto}/lib/python2.7/site-packages";
    AWS_BILLING_ACCESS_KEY = billing.access;
    AWS_BILLING_SECRET_KEY = billing.secret;
  };

  environment.etc."dd-agent/conf.d/lb-jobs-provisioner.yaml".source = generic-config;
  environment.etc."dd-agent/checks.d/lb-jobs-provisioner.py".source = ./lb-jobs-provisioner.py;

  environment.etc."dd-agent/conf.d/cloudwatch-billing.yaml".source = generic-config;
  environment.etc."dd-agent/checks.d/cloudwatch-billing.py".source = ./cloudwatch-billing.py;
}

