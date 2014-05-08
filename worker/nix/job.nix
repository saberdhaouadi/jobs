{ platform_version ? "3.10.13"
}:
let
  inherit (import <config/lib> {}) releases version buildLB pkgs;
  platform = builtins.getAttr platform_version releases.platform;
in
  pkgs.stdenv.mkDerivation rec {
    name = "job-${toString builtins.currentTime}";
    buildInputs = [
      pkgs.pythonFull
      platform.logicblox
      platform.bloxweb
      releases.pdxscience."4.0.0".pdxscience
      pkgs.socat
    ];

    LB_BLOXCOMPILER_SERVER="1";
    LB_MONITOR_RULE_TIME="5";

    GRB_LICENSE_FILE = pkgs.writeText "gurobi.lic" "TOKENSERVER=127.0.0.1";

    buildCommand = ''
      function start_lb() 
      {
        if which lb-services &> /dev/null ; then
          lbservices="lb-services"
        else
          lbservices="lb services"
        fi

        set +e
        cmd=start
        for i in $(seq 1 5); do
          echo "starting LogicBlox services [$i]"
          $lbservices $cmd &> /dev/null
          if [[ "$?" == "0" ]]; then
            break
          else
            cmd=restart
          fi
        done
        set -e
      }

      # a connection to the gurobi token server is exposed via an
      # unix domain socket at /sockets/gurobi
      if [[ -S /sockets/gurobi ]]; then
        socat tcp4-listen:41954,fork unix-connect:/sockets/gurobi &> /dev/null &
      fi

      start_lb
      tar --strip-components=1 -xf /tmp/job/job.tar.gz

      echo ""
      echo "running job"
      if [[ -f ./run ]]; then
        bash run /tmp/job/in /tmp/job/out
      else
        echo "ERROR: 'run' script not found in job!"
      fi

      rm -rf $out
      mkdir -p $out
    '';
    failureHook = exitHook;
    exitHook = ''
      chmod -R 777 . /tmp/job/out/*
      rm -f /tmp/LB_default_DaemonLock* || true
      rm -rf /dev/shm/LB_* || true
    '';
  }
