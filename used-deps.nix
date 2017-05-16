{ pkgs ? import <nixpkgs> {} }:
{
  R = pkgs.R ;
  ffmpeg = pkgs.ffmpeg ;
  pigz = pkgs.pigz ;
  pythonPackages.pandas = pkgs.pythonPackages.pandas ;
  pythonPackages.tensorflow = pkgs.pythonPackages.tensorflow ;
  rPackages.VIM = pkgs.rPackages.VIM ;
  rPackages.VIMGUI = pkgs.rPackages.VIMGUI ;
  rPackages.caret = pkgs.rPackages.caret ;
  rPackages.clusterSim = pkgs.rPackages.clusterSim ;
  rPackages.ggplot2 = pkgs.rPackages.ggplot2 ;
  rPackages.psych = pkgs.rPackages.psych ;
  rPackages.reshape = pkgs.rPackages.reshape ;
  rPackages.sqldf = pkgs.rPackages.sqldf ;
  sysstat = pkgs.sysstat ;
}
