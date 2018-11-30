{ platform_release ? import ./lb-version.nix
, benchmarks ? null
, heap_profiling ? false
, nixpkgs_1703 ? null
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB "4.7.0";
  #platform = (if (pkgs.lib.hasAttr "outPath" platform_release) then platform_release.outPath else (getLB platform_release));
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling nixpkgs_1703;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
