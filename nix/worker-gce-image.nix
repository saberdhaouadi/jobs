{ config, pkgs, lib, ...}:
let
  awsCreds =
    {
      environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      environment.AWS_SECRET_ACCESS_KEY = builtins.readFile <global_creds/gce-secret>;
      environment.AWS_REGION = "us-east-1";
    };
in
{
  imports = [
    ./worker.nix
    ./boot.nix
    <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
  ];

  lb-steve-worker.initrd.metadataServiceSetup =
    ''
      metaDir=$targetRoot/etc/ec2-metadata
      mkdir -m 0755 -p "$metaDir"

      echo "getting GCE instance metadata..."
      if ! [ -e "metaDir/instance-id" ]; then
        wget -q --header='Metadata-Flavor: Google' -O "metaDir/instance-id" http://metadata.google.internal/computeMetadata/v1/instance/id
      fi

      # use the same path for user data as EC2 to avoid duplicate code
      if ! [ -e "$metaDir/user-data" ]; then
        wget -q --header='Metadata-Flavor: Google' -O "$metaDir/user-data" http://metadata.google.internal/computeMetadata/v1/instance/attributes/startup-script
      fi
    '';

  lb-steve-worker.initrd.deviceDiscovery =
    ''
      devices=""
      nr=0
      mkdir -p /var/lock/lvm
      # looping only through local SSD nvme devices as it
      # looks like all GCE instances support that
      for device in /dev/nvme[0-9]n[0-9]; do
        echo $device
        if [ -e "$device" ];then
          lvm pvcreate -f $device
          devices="$devices $device"
          nr=$((nr+1))
        fi
      done
    '';

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
