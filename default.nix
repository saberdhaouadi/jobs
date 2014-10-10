{ src_s3lib ? <src_s3lib>
, platform_release ? <platform_release> # "4.1.1"
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getPlatform;
  platform = getPlatform platform_release;

  s3lib =
    let jobs = import src_s3lib { s3lib = src_s3lib; };
     in jobs.build;

in
  import ./job.nix {
    logicblox = platform.logicblox;
    lb_web = platform.bloxweb;
    inherit s3lib builder_config;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
