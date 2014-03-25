{ platform_version ? "3.10.9"
}:
let
  inherit (import <config/lib> {}) releases version buildLB pkgs;
  platform = builtins.getAttr platform_version releases.platform;
in
  pkgs.stdenv.mkDerivation {
    name = "job-${toString builtins.currentTime}";
    buildInputs = [
      platform.logicblox
      platform.bloxweb
      releases.pdxscience."4.0.0".pdxscience
    ];

    LB_BLOXCOMPILER_SERVER="1";
    LB_MONITOR_RULE_TIME="5";

    # enable gurobi and allow network
    GRB_LICENSE_FILE=./gurobi.lib;
    __noChroot = true;

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
        for i in $(seq 1 3); do
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

      start_lb
      tar --strip-components=1 -xf /tmp/job/job.tar.gz

      echo ""
      echo "running job"
      if [[ -f ./run ]]; then
        bash run /tmp/job/in /tmp/job/out
      else
        echo "ERROR: run.sh not found!"
      fi

      rm -rf $out
      mkdir -p $out
      chmod -R 777 . /tmp/job/out/*
    '';
  }
