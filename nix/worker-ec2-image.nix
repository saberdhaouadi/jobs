{ config, pkgs, lib, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/amazon-image.nix>
#    <lbdevops/logicblox/config/logging/logentries.nix>
  ];

  #FIXME revert once DEVOPS-43/LB-3068 are fixed
  boot.kernelPackages = pkgs.linuxPackages_4_14;

  #logging.logentries.logToken = builtins.readFile <global_creds/logentries-lb-jobs>;

  ec2.hvm = true;
  networking.hostName = pkgs.lib.mkForce "i-worker";

  lb-steve-worker.shutdownOnIdle = true;
  boot.initrd.kernelModules = [ "xen-blkfront" "xen-netfront" ];
  systemd.services.set-hostname =
    {
      before = [ "syslog.service" ];
      wantedBy = [ "multi-user.target" ];
      path = [ pkgs.wget pkgs.coreutils pkgs.nettools ];
      script = ''
        hostname=$(wget -q --retry-connrefused -t 6 --waitretry=10 -O -  http://169.254.169.254/latest/meta-data/instance-id)
        hostname $hostname
        cat > /tmp/hosts <<EOF
        127.0.0.1 $hostname localhost
        ::1 localhost
        EOF
        mv /tmp/hosts /etc/hosts
        ${pkgs.coreutils}/bin/kill -HUP `${pkgs.coreutils}/bin/cat /var/run/rsyslogd.pid` || true
      '';
      serviceConfig =
        { Type = "oneshot";
          RemainAfterExit = true;
        };
    };
  boot.blacklistedKernelModules = [ "nouveau" "xen_fbfront" ];
  boot.kernelParams = [ "console=ttyS0" "boot.trace" ];
  boot.initrd.availableKernelModules = [ "ixgbevf" "ena" "nvme" ];
  
  boot.initrd.extraUtilsCommands =
    ''
      cp --remove-destination ${pkgs.e2fsprogs}/sbin/mke2fs $out/bin
    '';

  boot.initrd.postMountCommands = pkgs.lib.mkOverride 0
    ''
      metaDir=$targetRoot/etc/ec2-metadata
      mkdir -m 0755 -p "$metaDir"

      echo "getting EC2 instance metadata..."

      if ! [ -e "$metaDir/ami-manifest-path" ]; then
        wget -q -O "$metaDir/ami-manifest-path" http://169.254.169.254/1.0/meta-data/ami-manifest-path
      fi

      if ! [ -e "$metaDir/user-data" ]; then
        wget -q -O "$metaDir/user-data" http://169.254.169.254/1.0/user-data && chmod 600 "$metaDir/user-data"
      fi

      if ! [ -e "$metaDir/hostname" ]; then
        wget -q -O "$metaDir/hostname" http://169.254.169.254/1.0/meta-data/hostname
      fi

      if ! [ -e "$metaDir/public-keys-0-openssh-key" ]; then
        wget -q -O "$metaDir/public-keys-0-openssh-key" http://169.254.169.254/1.0/meta-data/public-keys/0/openssh-key
      fi
      diskNr=0
      diskForUnionfs=
      for device in /dev/xvd[abcde]*; do
          if [ "$device" = /dev/xvda -o "$device" = /dev/xvda1 ]; then continue; fi
          fsType=$(blkid -o value -s TYPE "$device" || true)
          if [ "$fsType" = swap ]; then
              echo "activating swap device $device..."
              swapon "$device" || true
          elif [ "$fsType" = ext3 ]; then
              mp="/disk$diskNr"
              diskNr=$((diskNr + 1))
              if mountFS "$device" "$mp" "" ext3; then
                  if [ -z "$diskForUnionfs" ]; then diskForUnionfs="$mp"; fi
              fi
          else
              echo "skipping unknown device type $device"
          fi
      done
      if [ -n "$diskForUnionfs" ]; then
          mkdir -m 755 -p $targetRoot/$diskForUnionfs/root
          mkdir -m 1777 -p $targetRoot/$diskForUnionfs/root/tmp $targetRoot/tmp
          mount --bind $targetRoot/$diskForUnionfs/root/tmp $targetRoot/tmp
          if [ "$(cat "$metaDir/ami-manifest-path")" != "(unknown)" ]; then
              mkdir -m 755 -p $targetRoot/$diskForUnionfs/root/var $targetRoot/var
              mount --bind $targetRoot/$diskForUnionfs/root/var $targetRoot/var
              mkdir -p /unionfs-chroot/ro-nix
              mount --rbind $targetRoot/nix /unionfs-chroot/ro-nix
              mkdir -m 755 -p $targetRoot/$diskForUnionfs/root/nix
              mkdir -p /unionfs-chroot/rw-nix
              mount --rbind $targetRoot/$diskForUnionfs/root/nix /unionfs-chroot/rw-nix
              unionfs -o allow_other,cow,nonempty,chroot=/unionfs-chroot,max_files=32768 /rw-nix=RW:/ro-nix=RO $targetRoot/nix
          fi
      fi
     set +x
    '';


  system.build.amazonImage = import <nixpkgs/nixos/lib/make-disk-image.nix> {
    inherit pkgs lib config;
    diskSize = if config.ec2.hvm then 4096 else 8192;
    format = "qcow2";
    configFile = pkgs.writeText "configuration.nix"
      ''
        {
        }
      '';
  };

  # Datadog setup
  systemd.services.dd-agent.wantedBy = lib.mkForce [];
  systemd.services.dogstatsd.wantedBy = lib.mkForce [];

  services.dd-agent.enable = true;
  services.dd-agent.api_key = builtins.readFile <global_creds/datadog-lb>;
  services.dd-agent.tags = [
    "lb-jobs"
  ];

}
