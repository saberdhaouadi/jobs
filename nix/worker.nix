let
  pkgs = import <nixpkgs> {};
  builder-config = import <config> {};
  platform = builder-config.releases.platform."3.10.9";
  builds = import ../. {};

  workerScript =
    pkgs.writeScriptBin "worker" ''
      #! /bin/sh
      source /etc/profile
      export NIX_PATH="nixpkgs=${<nixpkgs>}:config=${<config>}:worker=${builds.worker}"
      ${builds.worker}/bin/lb-steve-worker
    '';

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
        pkgs.stdenv
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
          ExecStart = "${workerScript}/bin/worker";
        };
      };

      networking.hostName = pkgs.lib.mkForce "";

      systemd.services.set-hostname =
        { description = "Set hostname to instance-id";

          wantedBy = [ "multi-user.target" ];
          after = [ "network.target" ];

          path = [ pkgs.curl pkgs.coreutils pkgs.nettools ];

          script =
            ''
              hostname $(curl -s --retry 3 --retry-delay 10 -m 30 http://169.254.169.254/latest/meta-data/instance-id)
              if [[ -f /var/run/rsyslogd.pid ]]; then
                kill -HUP `cat /var/run/rsyslogd.pid`
              fi
            '';

          serviceConfig.Type = "oneshot";
          serviceConfig.RemainAfterExit = true;
        };

      time.timeZone = "UTC";
    };
in
  worker
