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
    buildCommand = ''
      echo ""
      echo "starting LogicBlox services"

      if which lb-services &> /dev/null ; then
        lb-services start &> /dev/null
      else
        lb services start &> /dev/null
      fi

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
