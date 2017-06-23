{ platform_release ? import ./lb-version.nix
, benchmarks ? null
, heap_profiling ? false
, nixpkgs_1703 ? null
, bench_duration ? 30*60
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB platform_release;
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling nixpkgs_1703 bench_duration;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
