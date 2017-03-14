{ platform_release ? import ./lb-version.nix
, benchmarks ? null
, heap_profiling ? false
, features ? ["perf"]
, bench_duration ? "1h"
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB platform_release;
  pkgs_new = import <nixpkgs_new> {};
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling features bench_duration;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
    mitmproxy = pkgs_new.pythonPackages.mitmproxy;
  }
