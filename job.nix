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

  data_dev =
    builder_config.fetchs3 {
      url = "s3://logicblox-private/data/lb-jobs-dev-20170110-145534.tgz";
      sha256 = "108n038x80w26n4qz4sszkwfhj97qp09m0ihkfypn4yx05nd2vnm";
    };

  requests_dev =
    builder_config.fetchs3 {
      url = "s3://logicblox-private/data/lb-jobs-dev-20170110-145534.requests";
      sha256 = "1b94knii6xs7lvd9zs3kn9d6hkilc8xvqrfn947da0bala809six";
    };

  data_dev_20170303-101311 =
    builder_config.fetchs3 {
      url = "s3://logicblox-private/data/lb-jobs-dev-20170303-101311.tgz";
      sha256 = "0pblw430hiv97w2y8dpfzjbgvdzc0w3h6pa15rwngbwzxq5683yc";
    };

  requests_dev_20170303-101311 =
    builder_config.fetchs3 {
      url = "s3://logicblox-private/data/lb-jobs-dev-20170303-101311.requests";
      sha256 = "0yksgfkzg1w02m5xpp26qzawbwbmhr4iymfy4fvsv9vd3ljcpwm5";
    };


  bench = data: name: precommand: command: id: attrs:
    let
      heap_profiling = true;
      bt = with pkgs; callPackage "${benchmarks}/benchmark-tools" {};
    in builder_config.buildLB (attrs // {
      inherit name;
      buildInputs = [ logicblox bt pkgs.bc pkgs.gperftools pkgs.binutils pkgs.ghostscript pkgs.graphviz pkgs.perl pkgs.pythonPackages.requests2 ] ++ (attrs.buildInputs or []);
      requiredSystemFeatures = ["perf"];
      LB_CONFIG = ./config/perf;
      buildCommand = ''
        function record_span()
        {
          local id="$1"
          shift

          local t1="$(date +%s.%N)"
          $@
          local t2="$(date +%s.%N)"

          if ! type -P bc &> /dev/null; then
            return
          fi

          local t3="$(echo "$t2 - $t1" | bc)"

          echo "''${id},''${t1},''${t2},''${t3}" >> ${id}-results.csv
        }


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

        pushd $LB_DEPLOYMENT_HOME
        mkdir exports
        pushd exports
        tar xvf ${data}
        ln -s 201* latest
        popd
        popd

        ${precommand}

        ${pkgs.lib.optionalString heap_profiling ''
          # Enable heap-profiling for throughput phase
          lb server stop
          mkdir hprof
          echo "Launching lb-server under heap-profiler"
          LD_LIBRARY_PATH=${pkgs.glibc}/lib \
          LD_PRELOAD=${pkgs.gperftools}/lib/libtcmalloc.so \
          HEAPPROFILE=hprof/lb-server.hprof \
            lb-server --daemonize false &
          sleep 60
        ''}
        ${if heap_profiling
            then "lb_server_pid=$(pgrep -f 'lb-server --daemonize')"
            else "lb_server_pid=$(cat $LB_DEPLOYMENT_HOME/logs/current/lb-server.pid)"}

        iousg-monitor  --iousg-pid  $lb_server_pid --iousg-out  iousg-monitor.csv  &
        cpuusg-monitor --cpuusg-pid $lb_server_pid --cpuusg-out cpuusg-monitor.csv &
        memusg-monitor --memusg-pid $lb_server_pid --memusg-out memusg-monitor.csv &

        ${command}

        pkill -f iousg-monitor
        pkill -f cpuusg-monitor
        pkill -f memusg-monitor

        mkdir -p $out/report

        ${pkgs.lib.optionalString heap_profiling ''
          pushd hprof
          ls -l
          t1_prof=$(ls lb-server.hprof.*.heap | head -n 3 | tail -n 1)
          t2_prof=$(ls lb-server.hprof.*.heap | tail -n 2 | head -n 1)

          pprof --pdf $LOGICBLOX_HOME/bin/lb-server $t1_prof > $out/report/$t1_prof.pdf
          pprof --pdf $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/$t2_prof.pdf
          pprof --pdf --alloc_space $LOGICBLOX_HOME/bin/lb-server $t1_prof > $out/report/$t1_prof-alloc.pdf
          pprof --pdf --alloc_space $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/$t2_prof-alloc.pdf
          pprof --pdf --base=$t1_prof $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/hprof-diff.pdf || true
          pprof --pdf --alloc_space --base=$t1_prof $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/hprof-diff-alloc.pdf || true
          popd

          pkill -f "lb-server --daemonize"
        ''}


        fancy_report ${id}

        dudir="$LB_DEPLOYMENT_HOME/workspaces"
        wssize=$(du -BM --max-depth=0 "$dudir" | sed 's/M//' | awk '{print $1}')
        echo "disk-usage-final,$wssize" >> $out/report/stats.csv

        mkdir -p $out/nix-support
        echo "file data $out/report/stats.csv" >> $out/nix-support/hydra-build-products

        tar -C $out/report -xvzf $out/report/${id}-report.tar.gz
        mv $out/report/report $out/report/${id}-report
        echo "doc ${id}-report $out/report/${id}-report" >> $out/nix-support/hydra-build-products
      '';
    });

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

    used-dependencies = import ./used-deps.nix { inherit pkgs; };

  } // ( pkgs.lib.optionalAttrs (benchmarks != null) {
/*
    benchmark.increasing-get-job =
      bench data "lb-jobs-get-job" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        for i in $(seq 1 100); do
          record_span "get-job-$i" python ${./frontend-database/scripts/test-get-job.py} $i
        done
      '' "metrics" {};

    benchmark.load-data =
      bench data "lb-jobs-install-with-data" "${jobs.database.build}/install.sh" "load" {};

    benchmark.get-metrics =
      bench data "lb-jobs-metrics-call" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 10000 http://localhost:55183/metrics
      '' "metrics" {};

    benchmark.get-metrics-2G =
      bench data "lb-jobs-metrics-call" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 10000 http://localhost:55183/metrics
      '' "metrics" { LB_MEM="2G"; };

    benchmark.dev-1000-jobs-run =
      bench data_dev "lb-jobs-1000-jobs-run" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        record_span "lb-jobs-1000-jobs" mitmdump -nc ${requests_dev}
      '' "metrics" { buildInputs = [ pkgs.pythonPackages.mitmproxy ]; };
*/
    benchmark.dev-walgreens-jobs-run-1h =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        record_span "lb-walgreens-jobs-1h" "timeout -k 60 1h mitmdump -nc ${requests_dev_20170303-101311} || true"
      '' "metrics" { buildInputs = [ pkgs.pythonPackages.mitmproxy ]; };
  });

in jobs
