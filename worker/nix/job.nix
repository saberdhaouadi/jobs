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
      lb-services start &> /dev/null

      cp -R ${job}/* .
      chmod u+w -R .

      echo ""
      echo "running job"
      bash run.sh

      mkdir -p $out
    '';
  }
