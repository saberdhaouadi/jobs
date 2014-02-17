{ name
, job
, platform_version ? "4.0.7"
, input ? "/tmp/in"
, output ? "/tmp/out"
}:
let
  inherit (import <config/lib> {}) releases version buildLB pkgs;
  platform = builtins.getAttr platform_version releases.platform;
in
  pkgs.stdenv.mkDerivation {
    inherit name;
    buildInputs = [
      platform.logicblox
      platform.bloxweb
    ];
    buildCommand = ''
      echo ""
      echo "starting LogicBlox services"
      lb services start &> /dev/null

      tar --strip-components=1 -xf ${job}

      echo ""
      echo "running job"
      if [[ -f ./run ]]; then
        bash run
      else
        echo "ERROR: run.sh not found!"
      fi

      mkdir -p $out
    '';
  }
