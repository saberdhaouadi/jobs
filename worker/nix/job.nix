{ platform_version
}:
let
  inherit (import <config/lib> {}) releases pkgs;
  platform = builtins.getAttr platform_version releases.platform;
  isFullPlatform = pkgs.lib.versionAtLeast platform_version "4.3.7";
in
  pkgs.stdenv.mkDerivation rec {
    name = "job-${toString builtins.currentTime}";
    buildInputs = [
      pkgs.pythonFull
      pkgs.pythonPackages.pandas
      pkgs.pythonPackages.scikitlearn
      pkgs.pythonPackages.matplotlib
      pkgs.pythonPackages.plotly
      pkgs.pythonPackages.statsmodels
      pkgs.socat
      pkgs.jq
      pkgs.curl
      pkgs.perl
      pkgs.fio
    ] ++ pkgs.lib.optional isFullPlatform releases.platforms."${platform_version}"
      ++ pkgs.lib.optionals (! isFullPlatform) [ platform.logicblox platform.bloxweb ]
      ++ pkgs.lib.optional ((pkgs.lib.substring 0 1 platform_version) == "3") releases.pdxscience."4.0.0".pdxscience;

    LB_MONITOR_RULE_TIME="30";
    LB_MEM="50%";

    GRB_LICENSE_FILE = pkgs.writeText "gurobi.lic" "TOKENSERVER=127.0.0.1";

    buildCommand = ''
      export HOME=$TMPDIR
      function start_lb() 
      {
        export LB_BLOXCOMPILER_SERVER=1;
        if type -P lb-services &> /dev/null ; then
          lbservices="lb-services"
        else
          lbservices="lb services"
        fi

        set +e
        cmd=start
        for i in $(seq 1 7); do
          echo "starting LogicBlox services [$i]"
          timeout -k 10 60 $lbservices $cmd &> /dev/null
          if [[ "$?" == "0" ]]; then
            set -e
            return
          else
            cmd=restart
            sleep 5
          fi
        done
        echo "INTERNAL_ERROR: Could not start LB services."
        set -e
        exit 1
      }

      # a connection to the gurobi token server is exposed via an
      # unix domain socket at /sockets/gurobi
      if [[ -S /sockets/gurobi ]]; then
        socat tcp4-listen:41954,fork unix-connect:/sockets/gurobi &> /dev/null &
      fi

      # check for no-services=true in metadata.json. if set, then do not start
      # services
      if [[ -f /tmp/job/in/metadata.json ]]; then
        NOSERVICES=$(cat /tmp/job/in/metadata.json | jq -r  '."no-services"')
      fi
      if [[ "$NOSERVICES" != "true" ]]; then
        start_lb
      fi

      tar --strip-components=1 -xf /tmp/job/job.tar.gz
      chmod -R u+w .

      echo ""
      echo "running job"
      if [[ -f ./run ]]; then
        bash run /tmp/job/in /tmp/job/out
      else
        echo "ERROR: 'run' script not found in job!"
        exit 1
      fi

      # hack to workaround size in steve with filesize 0
      for f in $(find /tmp/job/out -size 0); do echo "" > $f; done

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
