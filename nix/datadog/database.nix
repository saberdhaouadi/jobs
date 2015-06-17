{ config, pkgs, lib, ... }:
{
  systemd.services.dd-agent.environment.PYTHONPATH = "${pkgs.pythonPackages.requests}/lib/python2.7/site-packages";

  environment.etc."dd-agent/checks.d/lb-steve-database.py".source = ./lb-steve-database.py;
  environment.etc."dd-agent/conf.d/lb-steve-database.yaml".text = ''
    init_config:

    instances:
      [{}]
  '';

  environment.etc."dd-agent/conf.d/process.yaml".text = ''
    init_config:

    instances:
       - name: lb-server
         search_string: ['lb-server']

       - name: lb-pager
         search_string: ['lb-pager']
  '';
}

