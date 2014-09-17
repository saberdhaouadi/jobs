{ src ? ./.
, src_s3lib ? <src_s3lib>
, platform_release ? <platform_release> # "4.1.1"
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getPlatform;
  platform = getPlatform platform_release;
  jdk7_jce = pkgs.oraclejdk7.override (a: { installjce = true; }) ;

  s3lib =
    let jobs = import src_s3lib { s3lib = src_s3lib; };
     in jobs.build;

in
  import ./job.nix {
    inherit src;
    logicblox = platform.logicblox;
    lb_web = platform.bloxweb;
    jdk = jdk7_jce;
    inherit s3lib builder_config;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand;
  }
