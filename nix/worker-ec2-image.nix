{ config, pkgs, lib, ...}:
{
  imports = [
    ./worker.nix
    ./boot.nix
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

  boot.initrd.extraUtilsCommands =
    ''
      cp --remove-destination ${pkgs.e2fsprogs}/sbin/mke2fs $out/bin
    '';

  lb-steve-worker.initrd.metadataServiceSetup =
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
    '';

  lb-steve-worker.initrd.deviceDiscovery =
    ''
      devices=""
      nr=0
      mkdir -p /var/lock/lvm
      for device in /dev/xvd[bcdef]* /dev/nvme[0-9]n[0-9]; do
        echo $device
        if [ -e "$device" ]; then
          lvm pvcreate -f $device
          devices="$devices $device"
          nr=$((nr+1))
        fi
      done
    '';

  system.build.amazonImage = import <nixpkgs/nixos/lib/make-disk-image.nix> {
    inherit pkgs lib config;
    partitioned = config.ec2.hvm;
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
