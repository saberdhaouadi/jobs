{ stdenv
, fetchurl
, logicblox
, lb_web
, s3lib
, jdk
, unzip
, builder_config
, makeWrapper
, runCommand
, python
}:
let
  inherit (builder_config) pkgs;
  version = builder_config.version;
  bloxweb = lb_web;

  deps =
    import ./deps.nix {
      inherit pkgs;
    };


  makeClosure = module: buildFromConfig module (config: config.system.build.toplevel);

  scrubDrv = drv: let res = { inherit (drv) drvPath outPath type name system meta; outputName = "out"; out = res; }; in res;

  buildFromConfig = module: sel: scrubDrv (sel (import <nixpkgs/nixos/lib/eval-config.nix> {
    system = "x86_64-linux";
    modules = [ module dummy ] ++ pkgs.lib.singleton
      ({ config, lib, ... }:
      { fileSystems."/".device  = lib.mkDefault "/dev/sda1";
        boot.loader.grub.device = lib.mkDefault "/dev/sda";
      });
  }).config);

  dummy =
    {
      options = {
        deployment.storeKeysOnMachine = pkgs.lib.mkOption {
          default = false;
          type = pkgs.lib.types.bool;
          description = ''
          '';
        };
        ec2.metadata = pkgs.lib.mkOption {
          default = false;
          type = pkgs.lib.types.bool;
          description = ''
          '';
        };
      };
    };

in
rec {
  frontend =
     builder_config.buildLBConfig {
      name = "jobs-frontend";
      src = ./frontend;
      buildInputs = [ logicblox lb_web makeWrapper client.build worker pkgs.jq pkgs.scala_2_10 ];
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-frontend-database=${database.build}"
        "--with-s3lib=${s3lib}"
        "--with-commons-cli=${deps.commons-cli}"
      ];
      doCheck = true;
    };

  client.build =
    builder_config.buildLBConfig {
      name = "lb-steve-client";
      src = ./client;
      buildInputs = [ logicblox lb_web ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-s3lib=${s3lib}"
      ];
    };

  client.binary_tarball =
    builder_config.release_helper {
      name = "lb-steve-client";
      inherit (client) build;
    };

  protocols =
    builder_config.buildLBConfig {
      name = "jobs-protocols";
      src = ./protocols;
      buildInputs = [ logicblox lb_web ];
      enableLBservices = false;
      configureFlags = [
        "--with-s3lib=${s3lib}"
      ];
    };

  worker =
    builder_config.buildLBConfig {
      name = "jobs-worker";
      src = ./worker;
      buildInputs = [ logicblox lb_web makeWrapper ];
      enableLBservices = false;
      configureFlags = [
        "--with-commons-exec=${deps.commons-exec}"
        "--with-commons-cli=${deps.commons-cli}"
        "--with-protocols=${protocols}"
        "--with-s3lib=${s3lib}"
      ];
      postInstall = ''
        for b in lb-steve-worker lb-steve-provisioner; do 
          wrapProgram "$out/bin/$b" --prefix PATH : "${python}/bin:${jdk}/bin"
        done
      '';
    };

  worker_image.ec2 =
    let
      image = (import <nixpkgs/nixos> { system = "x86_64-linux"; configuration = ./nix/worker-ec2-image.nix; }).config.system.build.amazonImage;
    in 
      runCommand "worker-ec2-image" { preferLocalBuild = true; } ''
        mkdir -p $out/nix-support
        xz -z -c ${image}/nixos.img  > $out/worker.img.xz
        echo "file img $out/worker.img.xz" > $out/nix-support/hydra-build-products
      '';

  database =
    builder_config.genericAppJobset {
      inherit logicblox bloxweb;
      build = builder_config.buildLBConfig {
        name = "jobs-database";
        src = ./frontend-database;
        buildInputs = [ logicblox lb_web ];
        configureFlags = [
          "--with-protocols=${protocols}"
        ];
        doCheck = true;
      };
    };

  key-server =
     builder_config.buildLBConfig {
      name = "lb-steve-key-server";
      src = ./key-server;
      buildInputs = [ logicblox lb_web makeWrapper pkgs.jq pkgs.scala_2_10 ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-commons-cli=${deps.commons-cli}"
        "--with-s3lib=${s3lib}"
      ];
    };

  closures.worker =
    makeClosure (
      {config, pkgs, ...}:
      { imports = [ ./nix/worker.nix ];
      }
    );

}

