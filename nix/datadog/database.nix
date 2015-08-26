{ config, pkgs, lib, ... }:
{
  systemd.services.dd-agent.environment.PYTHONPATH = "${pkgs.pythonPackages.requests}/lib/python2.7/site-packages";

  environment.etc."dd-agent/checks.d/lb-steve-database.py".source = ./lb-steve-database.py;
  environment.etc."dd-agent/conf.d/lb-steve-database.yaml".text = ''
    init_config:

    instances:
      [{}]
  '';

}

