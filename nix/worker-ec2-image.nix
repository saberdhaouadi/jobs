{ config, pkgs, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/amazon-config.nix>
    <lbdevops/logicblox/config/logging/papertrail.nix>
  ];

  ec2.metadata = true;
  ec2.hvm = true;
  networking.hostName = pkgs.lib.mkForce "i-worker";

  lb-steve-worker.shutdownOnIdle = true;

  systemd.services.set-hostname =
    {
      before = [ "syslog.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ pkgs.wget pkgs.coreutils pkgs.nettools ];
      script = ''
        hostname $(wget -q --retry-connrefused -t 6 --waitretry=10 -O -  http://169.254.169.254/latest/meta-data/instance-id)
      '';
      serviceConfig =
        { Type = "oneshot";
          RemainAfterExit = true;
        };
    };

  boot.initrd.extraUtilsCommands =
    ''
      cp -v ${pkgs.e2fsprogs}/sbin/mke2fs $out/bin
    '';

  boot.initrd.postDeviceCommands =
    ''
      for device in /dev/xvd[bcde]*; do
        # If the disk image appears to be empty, run mke2fs to initialise.
        # This way instances with instance storage without a filesystem 
        # will behave the same on first boot, allowing nix store and tmp
        # to be on instance storage.
        FSTYPE=$(blkid -o value -s TYPE $device || true)
        if test -z "$FSTYPE"; then
            mke2fs -t ext3 $device
        fi
      done
    '';
}
