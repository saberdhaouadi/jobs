{ config, pkgs, lib, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/amazon-image.nix>
    <lbdevops/logicblox/config/logging/logentries.nix>
  ];

  logging.logentries.logToken = builtins.readFile <global_creds/logentries-lb-jobs>;

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
      cp --remove-destination ${pkgs.e2fsprogs}/sbin/mke2fs $out/bin
    '';

  boot.initrd.postMountCommands = pkgs.lib.mkOverride 0
    ''
      devices=""
      nr=0
      mkdir -p /var/lock/lvm
      for device in /dev/xvd[bcde]*; do
        echo $device
        lvm pvcreate -f $device
        devices="$devices $device"
        nr=$((nr+1))
      done

      lvm vgcreate raid $devices
      lvm lvcreate --zero n raid --name raid --extents '100%FREE' --stripes $nr
      lvm vgchange -ay raid

      diskForUnionfs=/disk0
      mke2fs -t ext4 /dev/dm-0
      mountFS /dev/dm-0 $diskForUnionfs ext4

      mkdir -m 755 -p $targetRoot/$diskForUnionfs/root
      mkdir -m 1777 -p $targetRoot/$diskForUnionfs/root/tmp $targetRoot/tmp
      mount --bind $targetRoot/$diskForUnionfs/root/tmp $targetRoot/tmp

      mkdir -m 755 -p $targetRoot/$diskForUnionfs/root/var $targetRoot/var
      mount --bind $targetRoot/$diskForUnionfs/root/var $targetRoot/var

      mkdir -p /unionfs-chroot/ro-nix
      mount --rbind $targetRoot/nix /unionfs-chroot/ro-nix

      mkdir -m 755 -p $targetRoot/$diskForUnionfs/root/nix
      mkdir -p /unionfs-chroot/rw-nix
      mount --rbind $targetRoot/$diskForUnionfs/root/nix /unionfs-chroot/rw-nix

      unionfs -o allow_other,cow,nonempty,chroot=/unionfs-chroot,max_files=32768 /rw-nix=RW:/ro-nix=RO $targetRoot/nix
    '';


  system.build.amazonImage = import <nixpkgs/nixos/lib/make-disk-image.nix> {
    inherit pkgs lib config;
    partitioned = config.ec2.hvm;
    diskSize = if config.ec2.hvm then 4096 else 8192;
    configFile = pkgs.writeText "configuration.nix"
      ''
        {
        }
      '';
  };

}
