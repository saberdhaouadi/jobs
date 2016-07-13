{ stdenv
, fetchurl
, logicblox
, jdk
, unzip
, builder_config
, makeWrapper
, runCommand
, python
, benchmarks
}:
let
  inherit (builder_config) pkgs;
  version = builder_config.version;

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
      };
    };

  data =
    builder_config.fetchs3 {
      url = "s3://logicblox-private/data/lb-jobs-20160713.tgz";
      sha256 = "1ql3zazlk4k8v72x2s8zl0519nmzcxh8qyg4m6c846j03jvlgrj2";
    };

  jobs = rec {

  frontend =
     builder_config.buildLBConfig {
      name = "jobs-frontend";
      src = ./frontend;
      buildInputs = [ logicblox makeWrapper client.build worker pkgs.jq pkgs.scala_2_10 ];
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-frontend-database=${database.build}"
        "--with-commons-cli=${deps.commons-cli}"
      ];
      doCheck = true;
    };

  client.build =
    builder_config.buildLBConfig {
      name = "lb-steve-client";
      src = ./client;
      buildInputs = [ logicblox ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-aws-java-sdk=${deps.aws-java-sdk}"
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
      buildInputs = [ logicblox ];
      enableLBservices = false;
    };

  worker =
    builder_config.buildLBConfig {
      name = "jobs-worker";
      src = ./worker;
      buildInputs = [ logicblox makeWrapper ];
      enableLBservices = false;
      configureFlags = [
        "--with-commons-exec=${deps.commons-exec}"
        "--with-commons-cli=${deps.commons-cli}"
        "--with-protocols=${protocols}"
        "--with-aws-java-sdk=${deps.aws-java-sdk}"
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
      inherit logicblox;
      build = builder_config.buildLBConfig {
        name = "jobs-database";
        src = ./frontend-database;
        buildInputs = [ logicblox ];
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
      buildInputs = [ logicblox makeWrapper pkgs.jq pkgs.scala_2_10 ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-commons-cli=${deps.commons-cli}"
      ];
    };

  closures.worker =
    makeClosure (
      {config, pkgs, ...}:
      { imports = [ ./nix/worker.nix ];
      }
    );

  } // ( pkgs.lib.optionalAttrs (benchmarks != null) {

  benchmark.install-with-data =
    let
      bt = with pkgs; callPackage "${benchmarks}/benchmark-tools" {};
    in builder_config.buildLB {
      name = "lb-jobs-install-with-data";
      buildInputs = [ logicblox bt pkgs.bc ];
      requiredSystemFeatures = ["perf"];
      LB_CONFIG = ./config/perf;
      buildCommand = ''
        function fancy_report_append_logs()
        {
          local id=$1
          local results=$2

          mkdir -p $id-report/logs
          cat $results >> $id-report/logs/results.csv
          cat $LB_DEPLOYMENT_HOME/logs/current/lb-server.log >> $id-report/logs/lb-server.log
          cat $LB_DEPLOYMENT_HOME/logs/current/lb-web-server.log >> $id-report/logs/lb-web-server.log
          cat iousg-monitor.csv >> $id-report/logs/iousg-monitor.csv
          cat cpuusg-monitor.csv >> $id-report/logs/cpuusg-monitor.csv
          cat memusg-monitor.csv >> $id-report/logs/memusg-monitor.csv

          if test -e $id-wf-logs; then
            set -x
            find $id-wf-logs -name '*.log' | xargs cp -t $id-report
          fi
        }

        function fancy_report_finalize()
        {
          local id=$1

          pushd $id-report
          lb-fancy-report logs report measure
          tar czf $out/report/$id-report.tar.gz report
          popd

          cp $id-report/logs/results.csv $out/report/$id-results.csv
          rm -rf $id-report
        }

        function fancy_report()
        {
          local id=$1
          cp $LB_DEPLOYMENT_HOME/logs/current/lb-server.log $out/report/$id-lb-server.log
          cp $LB_DEPLOYMENT_HOME/logs/current/lb-web-server.log $out/report/$id-lb-web-server.log
          log_analyzer.py -f $out/report/$id-lb-server.log extract_rules --logic -n 100 > $out/report/$id-top-rules.log
          gzip $out/report/$id-lb-server.log
          gzip $out/report/$id-lb-web-server.log

          fancy_report_append_logs $id $id-results.csv
          fancy_report_finalize $id
        }

        lb_server_pid=$(cat $LB_DEPLOYMENT_HOME/logs/current/lb-server.pid)
        iousg-monitor  --iousg-pid  $lb_server_pid --iousg-out  iousg-monitor.csv  &
        cpuusg-monitor --cpuusg-pid $lb_server_pid --cpuusg-out cpuusg-monitor.csv &
        memusg-monitor --memusg-pid $lb_server_pid --memusg-out memusg-monitor.csv &

        pushd $LB_DEPLOYMENT_HOME
        mkdir exports
        pushd exports
        tar xvf ${data}
        ln -s 20160608-083904 latest
        popd
        popd

        ${jobs.database.build}/install.sh

        pkill -f iousg-monitor
        pkill -f cpuusg-monitor
        pkill -f memusg-monitor

        mkdir -p $out/report

        fancy_report load

        dudir="$LB_DEPLOYMENT_HOME/workspaces"
        wssize=$(du -BM --max-depth=0 "$dudir" | sed 's/M//' | awk '{print $1}')
        echo "disk-usage-final,$wssize" >> $out/report/stats.csv

        cp iousg-monitor.csv cpuusg-monitor.csv memusg-monitor.csv $out/report

        iousg-monitor-plot  iousg-monitor.csv  $out/report/iousg  "lb-server"
        cpuusg-monitor-plot cpuusg-monitor.csv $out/report/cpuusg "lb-server"
        memusg-monitor-plot memusg-monitor.csv $out/report/memusg "lb-server"

        mkdir -p $out/nix-support
        for f in $out/report/*; do
          echo "file data $f" >> $out/nix-support/hydra-build-products
        done
      '';
    };
  });

in jobs
