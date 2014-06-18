{ config, pkgs, ...}:
{
  imports = [
    ./worker.nix
    <nixpkgs/nixos/modules/virtualisation/amazon-config.nix>
    <lbdevops/logicblox/config/logging/papertrail.nix>
  ];

  ec2.metadata = true;
  networking.hostName = pkgs.lib.mkForce "i-worker";

  lb-steve-worker.shutdownOnIdle = true;

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

}
