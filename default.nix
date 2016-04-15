{ platform_release ? import ./lb-version.nix
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB platform_release;
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
