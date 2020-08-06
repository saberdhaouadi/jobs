{ config, pkgs, resources, lib, ... }:
let
  builds = import ../. {};
in
{
  imports = [ ./builds.nix ];
  systemd.services =
  {
    lb-steve-frontend =
    {
      description = "LB Steve Frontend";
      after = [ "network.target" ];
      wantedBy = [ "multi-user.target" ];
      path = [ pkgs.jdk pkgs.bash config.logicblox.jobs.builds.frontend ];
      environment = {
        GOOGLE_APPLICATION_CREDENTIALS = "/run/keys/google";
        GCS_XML_ACCESS_KEY = builtins.readFile <global_creds/GCS_XML_ACCESS_KEY>;
        GCS_XML_SECRET_KEY = builtins.readFile <global_creds/GCS_XML_SECRET_KEY>;
      };
      preStart = ''
        mkdir -p /var/log/lb-steve-worker
      '';
      environment.JAVA_ARGS = "-server -Xmx4800m -Xss2048k -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -XX:+PreserveFramePointer";
      serviceConfig = {
        ExecStart = "${config.logicblox.jobs.builds.frontend}/bin/lb-steve-frontend --config ${config.system.build.frontendConfig}";
        Restart = "always";
        RestartSec = "10";
      };
    };
  };
}
