# metadata/0.1/meta-data/instance-id
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
      fi
      ${builds.worker}/bin/lb-steve-worker $WORKER_ARGS $@
    '';

  worker = 
    { config, pkgs, ... }:
    {
      imports = [
        <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
        <lbdevops/nixos/base/papertrail.nix>
      ];

      services.rsyslogd.enable = true;

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
        environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
        environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
        serviceConfig = {
          ExecStart = "${workerScript}/bin/worker";
        };
      };

      networking.hostName = pkgs.lib.mkForce "";

      systemd.services.sqs-return =
        { description = "Return SQS message in-flight.";

          wantedBy = [ "multi-user.target" ];
          after = [ "network.target" ];
          before = [ "shutdown.target" ];

          path = [ builds.worker ];

          environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
          environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;

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
