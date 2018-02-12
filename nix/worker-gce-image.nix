{ config, pkgs, lib, ...}:
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
      mv $diskImage nixos.raw
      popd
    '';
  };


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
