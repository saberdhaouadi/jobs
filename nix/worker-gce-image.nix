{ config, pkgs, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
    <lbdevops/nixos/base/papertrail.nix>
  ];

  ec2.metadata = true;
  networking.hostName = pkgs.lib.mkForce "";

  lb-steve-worker.shutdownOnIdle = true;

  systemd.services.lb-steve-worker =
    {
      environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
    };

  systemd.services.sqs-return =
    {
      environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
    };

}
