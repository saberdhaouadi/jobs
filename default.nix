{ platform_release ? import ./lb-version.nix
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getPlatform;
  platform = getPlatform platform_release;

  s3lib = platform.s3lib;
in
  import ./job.nix {
    logicblox = platform.logicblox;
    lb_web = platform.bloxweb;
    inherit s3lib builder_config;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
