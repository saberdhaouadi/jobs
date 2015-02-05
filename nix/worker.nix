{ config, pkgs, ... }:
with pkgs.lib;
let
  papertrail-crt = pkgs.fetchurl {
    url = https://papertrailapp.com/tools/syslog.papertrail.crt;
    md5 = "cee9b8d2d503188ccecbb22b49cd3bec";
  };

  builder-config = import <config> {};
  platform3 = builder-config.releases.platform."3.10.15";
  platform4 = builder-config.releases.platform."4.1.7";
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
        aws ec2 terminate-instances --region us-east-1 --instance-ids $(curl -s --retry 5 --retry-delay 5 -m 10 http://169.254.169.254/latest/meta-data/instance-id)
        systemctl poweroff
      '';

in
{
  imports = [ <lbdevops/logicblox/config/users.nix> ];

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
    environment.systemPackages = [
      platform3.logicblox
      platform3.bloxweb
      platform4.logicblox
      platform4.bloxweb
      builder-config.releases.pdxscience."4.0.0".pdxscience

      # actual packages
      builds.worker
      pkgs.stdenv
      pkgs.awscli
      shutdown-self
    ];

    # The jobs and their data cannot reasonably be passed in a pure
    # way, as the input and output data can be very big.
    nix.chrootDirs = [
      "/tmp/job"
      "/sockets=/run/sockets"
      "/usr/bin/env=${pkgs.coreutils}/bin/env"
      "/lib64/ld-linux-x86-64.so.2=${pkgs.glibc}/lib64/ld-linux-x86-64.so.2"
      "/bin/bash=${pkgs.bash}/bin/bash"
    ];
    nix.extraOptions = ''
      build-compress-log = false
    '';
    nix.useChroot = true;
    nix.package = pkgs.nixUnstable;

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
          RestartSec = "10";
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
      after = [ "network.target" "fetch-ec2-data.service" "gurobi-socket.service" ];
      requires = [ "gurobi-socket.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ builds.worker ];
      serviceConfig = {
        ExecStart = "${workerScript}/bin/worker ${optionalString cfg.shutdownOnIdle "--shutdown-on-idle"}";
        Restart = "always";
        RestartSec = "10";
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

    services.rsyslogd.enable = true;
    services.rsyslogd.extraConfig = ''
      $DefaultNetstreamDriverCAFile ${papertrail-crt}

      $ActionSendStreamDriver gtls
      $ActionSendStreamDriverMode 1
      $ActionSendStreamDriverAuthMode x509/name

      $ActionResumeInterval 10
      $ActionQueueSize 100000
      $ActionQueueDiscardMark 97500
      $ActionQueueHighWaterMark 80000
      $ActionQueueType LinkedList
      $ActionQueueFileName papertrailqueue
      $ActionQueueCheckpointInterval 100
      $ActionQueueMaxDiskSpace 2g
      $ActionResumeRetryCount -1
      $ActionQueueSaveOnShutdown on
      $ActionQueueTimeoutEnqueue 10
      $ActionQueueDiscardSeverity 0

      *.* @@logs.papertrailapp.com:24237
    '';
  };

}
