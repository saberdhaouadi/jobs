{ config, pkgs, resources, lib, ... }:
let
  builds = import ../. {};
in
{
  imports = [ ./builds.nix ];
  systemd.services =
  {
    lb-steve-key-server =
    {
      description = "LB Steve Frontend";
      after = [ "network.target" ];
      wantedBy = [ "multi-user.target" ];
      path = [ pkgs.jdk pkgs.bash config.logicblox.jobs.builds.frontend ];
      preStart = ''
          mkdir -p /var/log/lb-steve-key-server
      '';
      environment.JAVA_ARGS = "-Xmx4800m -Xss2048k -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false";
      serviceConfig = {
        ExecStart = "${config.logicblox.jobs.builds.key-server}/bin/lb-steve-key-server";
        Restart = "always";
        RestartSec = "10";
      };
    };
  };
}
