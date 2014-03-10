let
  pkgs = import <nixpkgs> {};
  builder-config = import <config> {};
  platform = builder-config.releases.platform."3.10.9";
  builds = import ../. {};
  worker = 
    { config, pkgs, ... }:
    {
      imports = [
        <nixpkgs/nixos/modules/virtualisation/amazon-config.nix>
        <lbdevops/nixos/base/papertrail.nix>
      ];

      services.rsyslogd.enable = true;
      ec2.metadata = true;

      environment.systemPackages = with platform; [
        builds.worker
        logicblox
        bloxweb
        builder-config.releases.pdxscience."4.0.0".pdxscience
      ];

      nix.chrootDirs = [ "/tmp/job"];
      nix.extraOptions = ''
        build-compress-log = false
      '';

      boot.devShmSize = "75%";

      systemd.services.lb-steve-worker = {
        description = "LB Steve Worker";
        after = [ "network.target" ];
        wantedBy = [ "multi-user.target" ];
        path = [ builds.worker ];
        serviceConfig = {
          ExecStart = pkgs.writeScript "start-worker" ''
            #! /bin/sh
            source /etc/profile
            export NIX_PATH="nixpkgs=${<nixpkgs>}:config=${<config>}:worker=${builds.worker}"
            ${builds.worker}/bin/lb-steve-worker
          '';
        };
      };

    };
in
  worker
