{ config, pkgs, ... }:
with pkgs.lib;
let
  builder-config = import <config> {};
  builds = import ../. { platform_release = builder-config.getLB (import ../lb-version.nix ); };

  cfg = config.lb-steve-worker;
  workerScript =
    pkgs.writeScriptBin "worker" ''
      #! /bin/sh
      set -e
      source /etc/profile
      export NIX_PATH="nixpkgs=${<nixpkgs>}:config=${<config>}:worker=${config.logicblox.jobs.builds.worker}:nixpkgs-unstable=${<nixpkgs-unstable>}"
      ${optionalString (config.deployment.targetEnv or "" == "") ''
        if [[ -f /root/user-data ]] ; then
          source /root/user-data
        elif [[ -f /etc/ec2-metadata/user-data ]]; then
          source /etc/ec2-metadata/user-data
        else
          exit 1
        fi
      ''}
      ${config.logicblox.jobs.builds.worker}/bin/lb-steve-worker ${cfg.arguments} $@
    '';

  platform3 = builder-config.releases.platform."3.10.15";

in
{
  imports = [
    <lbdevops/logicblox/config/users.nix>
    ./builds.nix
  ];

  options = {
    lb-steve-worker.shutdownOnIdle = mkOption {
      default = false;
      type = types.bool;
      description = ''
        Shutdown machine when lb-steve-worker has been idle.
      '';
    };
    lb-steve-worker.arguments = mkOption {
      default = "$WORKERARGS";
      type = types.str;
      description = ''
        Arguments to pass to lb-steve-worker.
      '';
    };
  };

  config = {

    # Adding packages that are used by the jobs to the system
    # closure, to make them immediately available.
    environment.systemPackages = [
      builder-config.releases.pdxscience."4.0.0".pdxscience
      platform3.logicblox
      platform3.bloxweb
      pkgs.protobuf2_5
      pkgs.fio
      pkgs.wireshark
      pkgs.mtr
      pkgs.inetutils
      pkgs.jdk
      (builder-config.releases.s3lib "4.3.3")

      # actual packages
      config.logicblox.jobs.builds.worker
      pkgs.stdenv
      pkgs.awscli
    ];

    # The jobs and their data cannot reasonably be passed in a pure
    # way, as the input and output data can be very big.
    nix = {
      sandboxPaths = [
        "/tmp/job"
        "/sockets=/run/sockets"
        "/usr/bin/env=${pkgs.coreutils}/bin/env"
        "/lib64/ld-linux-x86-64.so.2=${pkgs.glibc}/lib64/ld-linux-x86-64.so.2"
        "/bin/bash=${pkgs.bash}/bin/bash"
      ];
      extraOptions = ''
        build-compress-log = false
        user-agent-suffix = lb-jobs
        sandbox-dev-shm-size = 75%
      '';
      binaryCachePublicKeys = [ "bob.logicblox.com-1:pvQBnviKJObXHv3ZWBeCQ22pDFduyFTEb2XoJn3aOtI=" ];
      useSandbox = true;
      package = pkgs.nixUnstable;
      binaryCaches = [ "s3://logicblox-cache" ];
      trustedBinaryCaches = [ "s3://logicblox-cache" ];
    };

    systemd.extraConfig = ''
      DefaultCPUAccounting=true
      DefaultMemoryAccounting=true
    '';

    systemd.services.gurobi-socket =
      { description = "Create Gurobi unix domain socket";
        wants = [ "network-online.target" ];
        after = [ "network-online.target" ];
        wantedBy = [ "multi-user.target" ];
        path = [ pkgs.socat ];
        preStart =
          ''
            mkdir -p /run/sockets
            chmod 755 /run/sockets
          '';
        postStart =
          ''
            sleep 10
            chmod go+w-x /run/sockets/gurobi
          '';
        serviceConfig = {
          ExecStart = "${pkgs.socat}/bin/socat unix-listen:/run/sockets/gurobi,fork tcp-connect:gurobi.predictix.com:41954";
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

    systemd.services.check-rsyslog = {
      description = "Check closed TCP connections for rsyslogd";
      after = [ "network.target" "syslog.service" ];
      requires = [ "syslog.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ pkgs.lsof ];
      script = ''
        while true; do
          if lsof -i :24237 | grep CLOSE_WAIT ; then
            systemctl restart syslog.service
          fi
          sleep 60
        done
      '';
    };

    /**
    * Recent versions of LB web-client does more strict checking of the
    * host SSL certificate so we need to make sure that there is a
    * *.logicblox.com domain name that can be resolved for the key-server.
    */
    systemd.services.replace-etc-hosts =
      { description = "add a record for the key-server to /etc/hosts";
        wantedBy = [ "multi-user.target" "lb-steve-worker.service" ];
        before = [ "lb-steve-worker.service" ];
        script =
          ''
            etcHosts=$(mktemp)
            keyserver=$(grep KEYSERVICE /etc/ec2-metadata/user-data | cut -d\= -f2 | cut -d\/ -f3)
            cat /etc/hosts > $etcHosts
            echo "$keyserver keyserver.logicblox.com" >> $etcHosts
            rm /etc/hosts
            cat $etcHosts > /etc/hosts
          '';
        serviceConfig =
          { Type = "oneshot";
            RemainAfterExit = true;
          };
      };

    systemd.services.lb-steve-worker = {
      description = "LB Steve Worker";
      after = [ "network.target" "fetch-ec2-data.service" "gurobi-socket.service" ];
      wants = [ "gurobi-socket.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ config.logicblox.jobs.builds.worker ];
      environment = { LB_WEBCLIENT_HOME = config.logicblox.jobs.platform; };
      preStart = ''
        systemctl is-active gurobi-socket.service
      '';
      serviceConfig = {
        ExecStart = "${workerScript}/bin/worker ${optionalString cfg.shutdownOnIdle "--shutdown-on-idle"}";
        Restart = "always";
        RestartSec = "10";
        LimitNOFILE = 65536;
      };
    };

    systemd.services.sqs-return =
      { description = "Return SQS message in-flight.";

        wantedBy = [ "multi-user.target" ];
        after = [ "network.target" ];
        before = [ "shutdown.target" ];

        path = [ config.logicblox.jobs.builds.worker ];

        serviceConfig =
          { ExecStart = "${pkgs.coreutils}/bin/echo";
            ExecStop = "${workerScript}/bin/worker --return-job";
            Type = "oneshot";
            RemainAfterExit = true;
          };
      };

    time.timeZone = mkForce "UTC";

    nixpkgs.config.allowUnfree = true;
    nixpkgs.config.allowBroken = true;

    services.journald.rateLimitBurst = 0;

    services.logrotate.enable = true;
    services.logrotate.config = ''
      /var/log/messages {
        missingok
        hourly
        rotate 7
        compress
        sharedscripts
        postrotate
          ${pkgs.coreutils}/bin/kill -HUP `${pkgs.coreutils}/bin/cat /var/run/rsyslogd.pid`
        endscript
      }
    '';
  };

}
