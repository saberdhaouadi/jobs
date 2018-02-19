{ config, pkgs, ...}:
let
  cfg = config.lb-steve-worker;
in
with pkgs.lib;
{
  options = {
    lb-steve-worker.initrd.metadataServiceSetup = mkOption {
      default = "";
      type = types.lines;
      description = ''
        Initial script that will run as part of stage 1 boot process and fetch
        needed metadata from either GCE or EC2 instances.
      '';
    };
    lb-steve-worker.initrd.deviceDiscovery = mkOption {
      default = "";
      type = types.lines;
      description = ''
        A script that will lookup available ephemeral devices/local SSDs in
        EC2/GCP instances and add them to $devices variable which will be
        used later by a generic initrd post mount script.
      '';
    };
  };

  config = {
    boot.kernelPackages = pkgs.linuxPackages_4_14;

    boot.initrd.availableKernelModules = [ "nvme" ];

    boot.initrd.postMountCommands = pkgs.lib.mkOverride 0
      ( cfg.initrd.metadataServiceSetup
      +
        cfg.initrd.deviceDiscovery
      +
      ''
        set -x
        if [ -n "$devices" ]; then
          echo "vgcreate"
          lvm vgcreate raid $devices
          echo "lvcreate"
          lvm lvcreate -vvv --noudevsync --zero n raid --name raid --extents '100%FREE' --stripes $nr
          echo "vgchange"
          lvm vgchange --noudevsync -ay raid

          diskForUnionfs=/disk0
          echo "Creating ext4 filesystem on /dev/dm-0"
          mke2fs -t ext4 /dev/dm-0
          echo "Mounting /dev/dm-0 to $diskForUnionfs"
          mountFS /dev/dm-0 $diskForUnionfs "" ext4

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
        fi
        set +x
      '');
  };
}
