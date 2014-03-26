let
  pkgs = import <nixpkgs> {};
  builder-config = import <config> {};
  platform = builder-config.releases.platform."3.10.9";
  builds = import ../. {};

  workerScript =
    pkgs.writeScriptBin "worker" ''
      #! /bin/sh
      set -e
      source /etc/profile
      export NIX_PATH="nixpkgs=${<nixpkgs>}:config=${<config>}:worker=${builds.worker}"
      if [[ -f /root/user-data ]] ; then
        source /root/user-data
      else
        exit 1
      fi
      ${builds.worker}/bin/lb-steve-worker $WORKER_ARGS $@
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
        after = [ "network.target" "fetch-ec2-data.service" ];
        wantedBy = [ "multi-user.target" ];
        path = [ builds.worker ];
        serviceConfig = {
          ExecStart = "${workerScript}/bin/worker --shutdown-on-idle";
          Restart = "always";
          RestartSec = 5;
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
              hostname $(curl --retry 10 --retry-delay 10 -m 30 http://169.254.169.254/latest/meta-data/instance-id)
              if [[ -f /var/run/rsyslogd.pid ]]; then
                kill -HUP `cat /var/run/rsyslogd.pid`
              fi
            '';

          serviceConfig.Type = "oneshot";
          serviceConfig.RemainAfterExit = true;
        };


      systemd.services.sqs-return =
        { description = "Return SQS message in-flight.";

          wantedBy = [ "multi-user.target" ];
          after = [ "network.target" ];
          before = [ "shutdown.target" ];

          path = [ builds.worker ];

          serviceConfig =
            { ExecStart = "${pkgs.coreutils}/bin/echo";
              ExecStop = "${workerScript}/bin/worker --return-job";
              Type = "oneshot";
              RemainAfterExit = true;
            };
        };

      time.timeZone = "UTC";
    };
in
  worker
