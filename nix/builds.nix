{ config, lib, ... }:
with lib;
{
  options = {
    logicblox.jobs = {
      builds = mkOption {
        type = with types; attrsOf
          (either package (attrsOf package));
      };
      platform = mkOption {
        type = types.package;
      };
    };
  };

  config = {
    logicblox.jobs = {
      builds = mkDefault (import ../. { platform_release = logicblox.jobs.platform; });
      platform = mkDefault ((import <config> {}).getLB (import ../lb-version.nix));
    };
  };
}
