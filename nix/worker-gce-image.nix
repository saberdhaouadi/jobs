{ config, pkgs, lib, ...}:
let
  awsCreds =
    {
      environment.AWS_REGION = "us-east-1";
      serviceConfig.EnvironmentFile = "/run/keys/credentials";
    };

  udhcpcScript = pkgs.writeScript "udhcp-script"
    ''
      #! /bin/sh
      if [ "$1" = bound ]; then
        ip address add "$ip/$mask" dev "$interface"
        if [ -n "$staticroutes" ]; then
          echo $staticroutes | awk -e '{for(i=0; i< NF;i+=2) system("ip route add "$(i+1)" via "$(i+2)" dev '"$interface"' proto kernel scope link")}'
        fi
        if [ -n "$router" ]; then
          ip route add default via "$router" dev "$interface"
        fi
        if [ -n "$dns" ]; then
          rm -f /etc/resolv.conf
          for i in $dns; do
            echo "nameserver $dns" >> /etc/resolv.conf
          done
        fi
      fi
    '';

in
{
  imports = [
    ./worker.nix
    ./boot.nix
    <nixpkgs/nixos/modules/virtualisation/google-compute-config.nix>
    <lbdevops/logicblox/config/logging/rsyslogd.nix>
    ];

  logging.sumologic.sumoToken = builtins.readFile <global_creds/sumologic-lb-jobs-prod-workers>;
  logging.sumologic.collectorHost = "syslog.collection.us1.sumologic.com";

  environment.systemPackages =
    let
      shutdown-self =
        let curl = "curl -H 'Metadata-Flavor:Google' -s --retry 5 --retry-delay 5 -m 10";
        in pkgs.writeScriptBin "shutdown-self"
        ''
          #! /usr/bin/env bash
          instance=$(${curl} http://169.254.169.254/computeMetadata/v1/instance/name)
          zone=$(${curl} http://169.254.169.254/computeMetadata/v1/instance/zone | cut -d\/ -f4)
          ${pkgs.google-cloud-sdk-gce}/bin/gcloud compute instances delete $instance --zone=$zone --quiet
          systemctl poweroff
        '';
    in [ shutdown-self ];

  boot.initrd.kernelModules = [ "af_packet" ];
  boot.initrd.preLVMCommands = lib.mkBefore ''
            if [ -z "$hasNetwork" ]; then

          # Bring up all interfaces.
          for iface in $(cd /sys/class/net && ls); do
            echo "bringing up network interface $iface..."
            ip link set "$iface" up
          done

          # Acquire a DHCP lease.
          echo "acquiring IP address via DHCP..."
          udhcpc --quit --now --script ${udhcpcScript} && hasNetwork=1
        fi
      '';


  lb-steve-worker.initrd.metadataServiceSetup =
    ''
      metaDir=$targetRoot/etc/ec2-metadata
      mkdir -m 0755 -p "$metaDir"

      echo "getting GCE instance metadata..."
      if ! [ -e "$metaDir/instance-id" ]; then
        wget -q --header='Metadata-Flavor: Google' -O "$metaDir/instance-id" http://169.254.169.254/computeMetadata/v1/instance/id
      fi

      # use the same path for user data as EC2 to avoid duplicate code
      if ! [ -e "$metaDir/user-data" ]; then
        wget -q --header='Metadata-Flavor: Google' -O "$metaDir/user-data" http://169.254.169.254/computeMetadata/v1/instance/attributes/startup-script
      fi

      if ! [ -e "$metaDir/hostname" ]; then
        wget -q --header='Metadata-Flavor: Google' -O "$metaDir/hostname" http://169.254.169.254/computeMetadata/v1/instance/id
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

  /**
  * It is expected that the key-server has a file credentials.pem under
  * a fake account "google-worker-creds" which we use to pull the necessary
  * credentials for AWS/GCS access. The file should have the following structure:
  *   AWS_ACCESS_KEY_ID=<secret>
  *   AWS_SECRET_ACCESS_KEY=<secret>
  *   GCS_XML_ACCESS_KEY=<secret>
  *   GCS_XML_SECRET_KEY=<secret>
  */
  systemd.services.pull-credentials =
    {
      description = "download the credentials for AWS/GCS access from the key-server";
      wantedBy = [ "multi-user.target" ];
      script = ''
        set -eo pipefail
        keyserver=$(grep KEYSERVICE /etc/ec2-metadata/user-data | cut -d\= -f2 | tr -d '"')
        ${pkgs.curl}/bin/curl -XPOST \
          -k -H "Content-Type: application/json" \
          -d '{"account": "google-worker-creds"}' $keyserver \
          | ${pkgs.jq}/bin/jq -r '.key|.[]|select(.name=="credentials")|.contents' > /run/keys/credentials
      '';
    };

  systemd.services.set-hostname =
    {
      description = "set the instance hostname";
      wantedBy = [ "multi-user.target" ];
      script = ''
        if [ -s /etc/ec2-metadata/hostname ]; then
          ${pkgs.nettools}/bin/hostname $(cat /etc/ec2-metadata/hostname)
        fi
      '';
    };

  lb-steve-worker.shutdownOnIdle = true;
  users.mutableUsers = lib.mkOverride 0 false;

  users.extraUsers.root.openssh.authorizedKeys.keys = [
    # NOTE: Amine's ssh key
    # TODO: make it possible to grab ssh-keys from the metadata service while the instance
    # is running which will make it possible to add a public key from the cloud console.
    "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGLj6b2NxWaTh2epvC7DynHu//LKb8HOoXW03o2Q1DW8 amine@nixos"
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDfWqNlFX5VwJ/hq5vcmL8x1gxfr8aRKhaV/G+2oB1OvMAqv8DihER61beE1rgYTgnGvq74PshqVGTtJT+gTF8JMxYvZ76A/VOazPHrsprZQULctBNC5jQiHZ1pfEH9MXIMhQIq4nFx3jkC8eQzGwNk4hYEAbyQ9atq2T+Ig/9Wt/a7xPD+58QpkIpRRhdUBjW4e6NueTxZBLhRWjkQkugW8eoE7K7tmeIJQRAfbG0nS5pu96eNHyiraBJZdX7ZG2cqV8KEHLh6urRKFkoOybvyZwP7P6iZ7OkSxPPK65gA7BUa7ZRNMfW6240cB9Wwx0ZHuNmzZaHhNO/SOBh9a621 ahmedsamti@TNTNLASAMTI19"
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQCoyra8oase8mnMQQTp8PbmqThqGdy1+k3tT4SHNqm8rA4ZNE022JkuFCUVqhzweNHLuQS6vJY5WpJRRWQ12UBqdadq/tuvCOCS3/SqihS2rKde6ul2jeeuxDxOwLkEXUZ6dzrxr2aUsof9mTHd703D3gBtAVccb5SBoJZkQIH80nBR8zWXq/VOkxHIC97X7u2hBGjvNeWBFmevyusJytjOcxr2ihbKkqwDfwPVFoPsKIdN/WuK3/T8yK9vmSxJFWnGCtPWDqgeV+TNRDL2Qpc/xX7Kr97ltADUxprzNfYxf/EbEJ6HQs/RPDn1Y2hoSJfbToweGZEAO8IRpeEHDdpWt/4WqpUDSywDKzXywumX9++kT+n4S5xyfsb1eAjstpaf41xIyvTiqapSIfZdq+XEu/mfe5ghffedApDjFsLmuH1Dv8bKCUDW3KV3EewvJEEa+2w2nB3S0xgGVxkkI0yj+Oji5VGNs4O8tOOg6dPJcY/T7GZozuy4imaDlGn8wzhFYWoE/5PoB4l1d1iTeWtZGBM5mHeOc01OgeI8uUTdXsVBkK0pib6KnS2d5RqHOgM8Zln/YY/uceUsFW3thS199TRis5Sq7nPE76wZWaKSJ7PAdlvahVIkHBNZ3pfgSk4WVa44VkzmNk/tra4MnYGGqJD0iH0n9yCaJdcMseh8HQ== tewfik.ghariani@infor.com"
  ];

  system.build.googleComputeImage = import <nixpkgs/nixos/lib/make-disk-image.nix> {
    inherit pkgs lib config;
    diskSize = 1024 * 8; # FIXME: investigate why the closure size of the image is growing
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

  systemd.services.lb-steve-worker =
    {
      after = [ "pull-credentials.service" ];
      wants = [ "pull-credentials.service" ];
    } // awsCreds;

  systemd.services.sqs-return = awsCreds;

}
