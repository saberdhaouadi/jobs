{ config, pkgs, ... }:
with pkgs.lib;
let
  builder-config = import <config> {};
  platform = builder-config.releases.platform."3.10.12";
  builds = import ../. {};
  
  cfg = config.lb-steve-worker;
  workerScript =
    pkgs.writeScriptBin "worker" ''
      #! /bin/sh
      set -e
      source /etc/profile
      export NIX_PATH="nixpkgs=${<nixpkgs>}:config=${<config>}:worker=${builds.worker}"
      ${optionalString (config.deployment.targetEnv or "" == "") ''
        if [[ -f /root/user-data ]] ; then
          source /root/user-data
        else
          exit 1
        fi
      ''}
      ${builds.worker}/bin/lb-steve-worker ${cfg.arguments} $@
    '';

  shutdown-self =
    pkgs.writeScriptBin "shutdown-self"
      ''
        #! /bin/sh
        aws ec2 terminate-instances --instance-ids $(curl -s --retry 5 --retry-delay 5 -m 10 http://169.254.169.254/latest/meta-data/instance-id)
        systemctl poweroff
      '';

in
{
  options = {
    lb-steve-worker.shutdownOnIdle = mkOption {
      default = false;
      type = types.bool;
      description = "
        Shutdown machine when lb-steve-worker has been idle.
      ";
    };
    lb-steve-worker.arguments = mkOption {
      default = "$WORKERARGS";
      type = types.str;
      description = "
        Arguments to pass to lb-steve-worker.
      ";
    };
  };

  config = {
    # Adding packages that are used by the jobs to the system
    # closure, to make them immediately available.
    environment.systemPackages = with platform; [
      builds.worker
      logicblox
      bloxweb
      builder-config.releases.pdxscience."4.0.0".pdxscience
      pkgs.stdenv
      pkgs.awscli
      shutdown-self
    ];

    # The jobs and their data cannot reasonably be passed in a pure
    # way, as the input and output data can be very big.
    nix.chrootDirs = [ "/tmp/job" "/sockets=/run/sockets" "/usr/bin/env=${pkgs.coreutils}/bin/env"];
    nix.extraOptions = ''
      build-compress-log = false
    '';
    nix.useChroot = true;

    systemd.services.gurobi-socket =
      { description = "Create Gurobi unix domain socket";
        wantedBy = [ "multi-user.target" ];
        path = [ pkgs.socat ];
        preStart =
          ''
            mkdir -p /run/sockets
            chmod 755 /run/sockets
          '';
        postStart =
          ''
            chmod go+w-x /run/sockets/gurobi
          '';
        serviceConfig = {
          ExecStart = "${pkgs.socat}/bin/socat unix-listen:/run/sockets/gurobi,fork tcp-connect:ec2-50-17-61-66.compute-1.amazonaws.com:41954";
          Restart = "always";
          RestartSec = "2";
        };
      };

    # LogicBlox needs /dev/shm to be at least 75% of total memory.
    boot.devShmSize = "75%";

    # Directory is needed in case nix tries to build something, otherwise
    # the chroot setup fails.
    system.activationScripts.job-directory = 
      ''
        mkdir -p /tmp/job
      '';

    # 
    systemd.services.lb-steve-worker = {
      description = "LB Steve Worker";
      after = [ "network.target" "fetch-ec2-data.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ builds.worker ];
      serviceConfig = {
        ExecStart = "${workerScript}/bin/worker ${optionalString cfg.shutdownOnIdle "--shutdown-on-idle"}";
        Restart = "always";
        RestartSec = 5;
      };
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

    nixpkgs.config.allowUnfree = true;
  };
}
