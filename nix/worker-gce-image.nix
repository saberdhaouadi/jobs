{ config, pkgs, lib, ...}:
let
  awsCreds =
    {
      environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
      environment.AWS_REGION = "us-east-1";
    };
in
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
  ];

  networking.hostName = pkgs.lib.mkForce "";

  lb-steve-worker.shutdownOnIdle = true;
  users.mutableUsers = lib.mkOverride 0 false;

  system.build.googleComputeImage = import <nixpkgs/nixos/lib/make-disk-image.nix> {
    inherit pkgs lib config;
    diskSize = 4096;
    format = "raw";
    configFile = pkgs.writeText "configuration.nix"
      ''
        {
        }
      '';
    postVM = ''
      pushd $out
      mv $diskImage disk.raw
      popd
    '';
  };

  systemd.services.nix-daemon = awsCreds;

  systemd.services.lb-steve-worker = awsCreds;

  systemd.services.sqs-return = awsCreds;

}
