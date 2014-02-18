{ src ? ./. }:
let
  inherit (import <config> {}) releases pkgs version buildLBConfig;
  platform = releases.platform."4.0.7";
in
{
  frontend =
    buildLBConfig {
      name = "jobs-frontend-${version src}";
      src = ./frontend;
      buildInputs = with platform; [ logicblox bloxweb ];
    };

  worker =
    buildLBConfig {
      name = "jobs-worker-${version src}";
      src = ./worker;
      buildInputs = with platform; [ logicblox bloxweb ];
    };
  
}
