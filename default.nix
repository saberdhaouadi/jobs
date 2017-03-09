{ platform_release ? import ./lb-version.nix
, benchmarks ? null
, heap_profiling ? false
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB platform_release;
  pkgs_new ? import <nixpkgs_new> {};
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
    mitmproxy = pkgs_new.pythonPackages.mitmproxy;
  }
