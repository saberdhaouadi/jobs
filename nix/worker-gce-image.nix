{ config, pkgs, lib, ...}:
let
  awsCreds =
    {
      environment.AWS_ACCESS_KEY_ID = builtins.readFile <global_creds/gce-access>;
      environment.AWS_SECRET_ACCESS_KEY = builtins.readFile <global_creds/gce-secret>;
      environment.AWS_SECRET_KEY = builtins.readFile <global_creds/gce-secret>;
      environment.AWS_REGION = "us-east-1";
      environment.GCS_XML_ACCESS_KEY = builtins.readFile <global_creds/gcs-access>;
      environment.GCS_XML_SECRET_KEY = builtins.readFile <global_creds/gcs-secret>;
      environment.GOOGLE_APPLICATION_CREDENTIALS = "/etc/google_application_credentials.json";

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
  ];
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
  environment.etc."google_application_credentials.json".text = builtins.readFile <global_creds/gcp-creds.json>;

  lb-steve-worker.shutdownOnIdle = true;
  users.mutableUsers = lib.mkOverride 0 false;

  # FIXME: temporarily set an ssh key for debugging
  users.extraUsers.root.openssh.authorizedKeys.keys = [
    "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAACAQDJyDNZrv9TduFS+yo0R2C4Iac10i+5PfxSOeJguFCpT5gnTfAKktaHrx83mD7Ypo8lsUy09DyGV0KWHNvAOneKjKIX1/eHw0M0NEJvRClpM26gy4DKzTs3mwc7JLFMobYgtvI4WsmwGow8fi8T/S749r7CFrwtF33hEzwUNa55+k82+pM554mi2iOAiIKTflVUXUIu89P08yVK2R5ttEv/A43VEAUrsPjYXRTRqF3ANvxCuzqwUVzc6707+6vaup/Qx2bJ9Muz/fZzLgMDrLVuZVxr4hQeEtesQK6+iWLSkQA4NbFt/22jyWffzQMVHBJrZIdmH+KHaoPGjDwMQ/owNOTRlWBKcLqazlPT3IKa6Tlwfeo+Hs/2bUZhs7tEs9UyS3o1c04HxIQStzTaV77DI0t+JggIm42RFKAZlM2S0X+g8Z/uGLrZ8zbxQhxRnUZq+5ZfBO+PsqSysf/tiimyEzQy5mdEKIgJtFgzEC4lg3h8RVGhexo/VPzkkLQPIJK+H6HGvVAZD2CtCmycryGPlmQe5Lkf7vALLAZxvyt8QzuwOKCU35tFFwez/VGH7iAxLlADWYwKt2Ei/fcmqt8XQyFTbIHFMj82SfkMHDftdn5UKW8NuyXpvumL8Tu+Sp+abaatmuD8ofD3TWdq5HMlL8ZfKfTohvzcs6iD9WQTbw== cardno:00061136155"
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

  systemd.services.lb-steve-worker = awsCreds;

  systemd.services.sqs-return = awsCreds;

}
