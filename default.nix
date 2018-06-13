{ platform_release ? import ./lb-version.nix
, benchmarks ? null
, heap_profiling ? false
, nixpkgs_1703 ? null
}:
let
  builder_config = import <config> {};
  inherit (builder_config) pkgs getLB;
  platform = (builtins.storePath /nix/store/pdcw7838mqq5ni9nknh2jykv8lfq1sff-logicblox-4.4.17-6b3c1e440328ddb65182b5915437b25ab6058ded);# getLB platform_release;
in
  import ./job.nix {
    logicblox = platform;
    inherit builder_config benchmarks heap_profiling nixpkgs_1703;
    inherit (pkgs) python stdenv fetchurl unzip makeWrapper runCommand jdk;
  }
