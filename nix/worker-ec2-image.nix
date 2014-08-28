{ config, pkgs, ...}:
let
  papertrail-crt = pkgs.fetchurl {
    url = https://papertrailapp.com/tools/syslog.papertrail.crt;
    md5 = "cee9b8d2d503188ccecbb22b49cd3bec";
  };
in
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/amazon-config.nix>
  ];

  ec2.metadata = true;
  ec2.hvm = true;
  networking.hostName = pkgs.lib.mkForce "i-worker";

  lb-steve-worker.shutdownOnIdle = true;

  services.rsyslogd.enable = true;
  services.rsyslogd.extraConfig =
    ''
      $DefaultNetstreamDriverCAFile ${papertrail-crt}

      $ActionSendStreamDriver gtls
      $ActionSendStreamDriverMode 1
      $ActionSendStreamDriverAuthMode x509/name

      $ActionResumeInterval 10
      $ActionQueueSize 100000
      $ActionQueueDiscardMark 97500
      $ActionQueueHighWaterMark 80000
      $ActionQueueType LinkedList
      $ActionQueueFileName papertrailqueue
      $ActionQueueCheckpointInterval 100
      $ActionQueueMaxDiskSpace 2g
      $ActionResumeRetryCount -1
      $ActionQueueSaveOnShutdown on
      $ActionQueueTimeoutEnqueue 10
      $ActionQueueDiscardSeverity 0

      *.* @@logs.papertrailapp.com:24237
    '';

  systemd.services.lb-steve-worker =
    {
      path = [ pkgs.curl pkgs.coreutils pkgs.nettools ];
      preStart = ''
        hostname $(curl --retry 5 --retry-delay 5 -m 10 http://169.254.169.254/latest/meta-data/instance-id)
        if [[ -f /var/run/rsyslogd.pid ]]; then
          kill -HUP `cat /var/run/rsyslogd.pid`
        fi
      '';
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
