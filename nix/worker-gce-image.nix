{ config, pkgs, lib, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
  ];

  networking.hostName = pkgs.lib.mkForce "";

  lb-steve-worker.shutdownOnIdle = true;
  users.mutableUsers = lib.mkOverride 0 false;

  systemd.services.lb-steve-worker =
    {
      #environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      #environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
    };

  systemd.services.sqs-return =
    {
      #environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      #environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
    };

}
