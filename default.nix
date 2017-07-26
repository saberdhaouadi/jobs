# { platform_release ? import ./lb-version.nix
{ src_logicblox
, src_lb_web_original
, src_s3lib
, src_datalog_generator
, benchmarks ? null
, heap_profiling ? false
, nixpkgs_1703 ? null
, bench_duration ? 30*60
, extraDefines ? []
, logicblox_config ? "release"
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;

  integration_jobset =
    import (src_builder_config + "/integration/jobsets.nix") {
      inherit src_logicblox src_lb_web_original src_s3lib src_datalog_generator;
      args_logicblox = {
        inherit extraDefines;
        config = logicblox_config;
      };
    };

  platform = integration_jobset.platform;

  # platform = getLB platform_release;
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling nixpkgs_1703 bench_duration;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
