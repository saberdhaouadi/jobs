{ config, pkgs, lib, resources, nodes, ... }:
let
  builder-config = import <config> {};
  updateLBversions = pkgs.writeScriptBin "update-lb-versions" ''
    #! /usr/bin/env bash
    set -ex

    function exit_trap()
    {
      rm -f $PLATFORM_RELEASES
    }
    trap exit_trap EXIT

    export PLATFORM_RELEASES=$(mktemp)
    ${pkgs.awscli}/bin/aws s3 cp s3://${config.system.build.s3Name}/override/platform-releases.nix $PLATFORM_RELEASES

    export CSV=$(nix-build ${./lb-versions.nix} --no-out-link)
    if [[ -n "$CSV" ]] ; then
      lb web-client import -i $CSV http://localhost:8080/tdx/platform_versions
    fi
  '';
in
{
  imports = [
    <lbdevops/nixos/logicblox/lb40-module.nix>
    <lbdevops/nixos/logicblox/installer.nix>
    ./builds.nix
  ] ;

  environment.systemPackages = [ updateLBversions ];

  services.logicblox.enable = true;
  services.logicblox.logicblox = config.logicblox.jobs.platform;
  services.logicblox.config.lb-server = ''
    [workspace]
    auto_backup_mode=none
  '';

  services.logicblox.config.lb-web-server = ''
    [statsd]
    prefix = lb.web
    hostname = 127.0.0.1
    port = 8125
  '';

  logicblox.application.installer = (config.logicblox.jobs.builds).database.build;
  services.nginx.enable = lib.mkOverride 0 false;

  networking.firewall.allowedTCPPorts = [ 8080 55183 80 ];
}
