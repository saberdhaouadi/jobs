{ platform_release ? import ./lb-version.nix
, benchmarks ? null
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = getLB platform_release;
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
