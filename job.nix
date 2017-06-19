{ stdenv
, fetchurl
, logicblox
, lb_web ? null
, s3lib ? null
, jdk
, unzip
, builder_config
, makeWrapper
, runCommand
, python
, benchmarks ? null
, heap_profiling ? false
, nixpkgs_1703 ? null
}:
let
  inherit (builder_config) pkgs;
  version = builder_config.version;

  mitmproxy = (import (if nixpkgs_1703 != null then nixpkgs_1703 else <nixpkgs>) {}).pythonPackages.mitmproxy; 

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
      bt = with pkgs; callPackage "${benchmarks}/benchmark-tools" {};
    in builder_config.buildLB (attrs // {
      inherit name;
      buildInputs = [ logicblox bt pkgs.bc pkgs.gperftools pkgs.binutils pkgs.ghostscript pkgs.graphviz pkgs.perl pkgs.pythonPackages.requests2 ] ++ (attrs.buildInputs or []);
      requiredSystemFeatures = ["perf"];
      # LB_CONFIG = ./config/perf;
      buildCommand = ''
        function record_span()
        {
          local id="$1"
          shift

          local t1="$(date +%s.%N)"
          ${pkgs.lib.optionalString (attrs ? timeout) "timeout ${toString attrs.timeout}"} "$@"
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
          cp $id-report/logs/iousg-monitor.csv  $out/report/$id-iousg-monitor.csv
          cp $id-report/logs/cpuusg-monitor.csv $out/report/$id-cpuusg-monitor.csv
          cp $id-report/logs/memusg-monitor.csv $out/report/$id-memusg-monitor.csv
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

        function tracing_json_publish()
        {
          name=$1
          cat $LB_DEPLOYMENT_HOME/logs/current/lb-web-server.log | tr -s ' ' | cut -d ' ' -f6- > lb-web-server-tr.log
          trace2json.py \
            lb-web-server-tr.log \
            $LB_DEPLOYMENT_HOME/logs/current/lb-server.log \
            > $1.json
          mkdir -p $out/nix-support $out/report
          cp $1.json $out/report
          echo "file json $out/report/$1.json" >> $out/nix-support/hydra-build-products
        }

        set -x

        pushd $LB_DEPLOYMENT_HOME
        mkdir exports
        pushd exports
        tar xvf ${data}
        ln -s 201* latest
        ${pkgs.lib.optionalString ((attrs ? dataset_multiplier) && (attrs.dataset_multiplier > 1)) ''
          # Scale dataset
          cd 201*
          job_csvs="jobs job_metadata job_inputs job_outputs job_status"
          for c in $job_csvs; do
            for i in $(seq $dataset_multiplier); do
              # Update job ID (1st column)
              tail -n +2 $c.csv | sed "s/^\"\([^\"]*\)\"|/\"\1-$i\"|/" > $c-$i.csv
              if [ "$c" = "jobs" ]; then
                # CLIENTID (5th column) should be unique too
                sed -i "s/\"\([^\"]*\)\"/\"\1-$i\"/5" $c-$i.csv
              fi
            done
            head -1 $c.csv > tmp-$c.csv
            cat $c-*.csv >> tmp-$c.csv
            mv tmp-$c.csv $c.csv
          done
        ''}
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

        function start_monitors() {
          iousg-monitor  --iousg-pid  $lb_server_pid --iousg-out  iousg-monitor.csv  &
          cpuusg-monitor --cpuusg-pid $lb_server_pid --cpuusg-out cpuusg-monitor.csv &
          memusg-monitor --memusg-pid $lb_server_pid --memusg-out memusg-monitor.csv &
        }

        function stop_monitors() {
          pkill -f iousg-monitor
          pkill -f cpuusg-monitor
          pkill -f memusg-monitor
        }

        function restart_services() {
          stop_monitors
          sleep 2
          lb services restart
          start_monitors
        }

        start_monitors

        ${command}

        stop_monitors

        mkdir -p $out/report

        ${pkgs.lib.optionalString heap_profiling ''
          pushd hprof
          ls -l
          t1_prof=$(ls lb-server.hprof.*.heap | head -n 6 | tail -n 1)
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
        mv $out/report/report $out/${id}-report
        rm $out/report/${id}-report.tar.gz
        echo "doc ${id}-report $out/${id}-report" >> $out/nix-support/hydra-build-products

        pushd $out
        tar czf ${id}-report-all.tar.gz report
        popd
        echo "file tgz $out/${id}-report-all.tar.gz" >> $out/nix-support/hydra-build-products
      '';
    });

  scala =
    pkgs.scala_2_12 or (pkgs.lib.overrideDerivation pkgs.scala (a: rec {
      name = "scala-2.12.2";

      src = fetchurl {
        url = "http://www.scala-lang.org/files/archive/${name}.tgz";
        sha256 = "1xd68q9h0vzqndar3r4mvabbd7naa25fbiciahkhxwgw8sr6hq8r";
      };
    }));

  postInstall = ''
    mkdir $out/findbugs
    cp build/*-findbugs.html $out/findbugs
  '';

  jobs = rec {

  frontend =
     builder_config.buildLBConfig {
      name = "jobs-frontend";
      src = ./frontend;
      buildInputs = [ logicblox makeWrapper client.build worker pkgs.jq scala pkgs.findbugs ];
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-frontend-database=${database.build}"
        "--with-commons-cli=${deps.commons-cli}"
      ];
      doCheck = true;
      inherit postInstall;
    };

  client.build =
    builder_config.buildLBConfig {
      name = "lb-steve-client";
      src = ./client;
      buildInputs = [ logicblox lb_web pkgs.findbugs ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-aws-java-sdk=${deps.aws-java-sdk}"
      ];
      inherit postInstall;
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
      buildInputs = [ logicblox lb_web pkgs.findbugs ];
      enableLBservices = false;
      inherit postInstall;
    };

  worker =
    builder_config.buildLBConfig {
      name = "jobs-worker";
      src = ./worker;
      buildInputs = [ logicblox makeWrapper pkgs.findbugs ];
      enableLBservices = false;
      configureFlags = [
        "--with-commons-exec=${deps.commons-exec}"
        "--with-commons-cli=${deps.commons-cli}"
        "--with-protocols=${protocols}"
        "--with-aws-java-sdk=${deps.aws-java-sdk}"
      ];
      postInstall = postInstall + ''
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
        doCheck = false;
      };
    };

  key-server =
     builder_config.buildLBConfig {
      name = "lb-steve-key-server";
      src = ./key-server;
      buildInputs = [ logicblox makeWrapper pkgs.jq scala pkgs.findbugs ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-commons-cli=${deps.commons-cli}"
      ];
      inherit postInstall;
    };

  closures.worker =
    makeClosure (
      {config, pkgs, ...}:
      { imports = [ ./nix/worker.nix ];
      }
    );

  used-dependencies = import ./used-deps.nix { inherit pkgs; };

  findbugs = pkgs.runCommand "findbugs-combine" {} ''
    mkdir -p $out/nix-support
    cp ${worker}/findbugs/*.html $out
    cp ${client.build}/findbugs/*.html $out
    cp ${key-server}/findbugs/*.html $out
    cp ${frontend}/findbugs/*.html $out
    cp ${protocols}/findbugs/*.html $out

    for f in $out/*.html; do
      echo "report $(basename $f .html) $f" >> $out/nix-support/hydra-build-products
    done
  '';

  } // ( pkgs.lib.optionalAttrs (benchmarks != null) {

    benchmark.increasing-get-job =
      bench data "lb-jobs-get-job" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        for i in $(seq 1 100); do
          record_span "get-job-$i" python ${./frontend-database/scripts/test-get-job.py} $i
        done
        tracing_json_publish "increasing-get-job"
      '' "metrics" {};

    benchmark.load-data =
      bench data "lb-jobs-install-with-data" "" ''
        ${jobs.database.build}/install.sh
        tracing_json_publish "load-data"
      '' "load" {};

    benchmark.get-metrics =
      bench data "lb-jobs-metrics-call" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 10000 http://localhost:55183/metrics
        record_span "get-metrics-100000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 100000 http://localhost:55183/metrics
      '' "metrics" {};

    benchmark.get-metrics-2G =
      bench data "lb-jobs-metrics-call" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 20 -n 10000 http://localhost:55183/metrics
      '' "metrics" { LB_MEM="2G"; };

    benchmark.dev-1000-jobs-run =
      bench data_dev "lb-jobs-1000-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        record_span "lb-jobs-1000-jobs-1" mitmdump -nc ${requests_dev}
        record_span "lb-jobs-1000-jobs-2" mitmdump -nc ${requests_dev}
        record_span "lb-jobs-1000-jobs-3" mitmdump -nc ${requests_dev}
        tracing_json_publish "dev-1000-jobs-run"
      '' "metrics" { buildInputs = [ mitmproxy ]; };

    benchmark.dev-walgreens-jobs =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311}
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 7200; meta.timeout = 18000; };
 
    benchmark.dev-walgreens-jobs-run =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311}
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 7200; meta.timeout = 18000; };

    benchmark.dev-walgreens-jobs-install =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 7200; meta.timeout = 18000; };
 
    benchmark.dev-walgreens-jobs-install-data4x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 36000; dataset_multiplier = 4; meta.timeout = 36000; };

    benchmark.dev-walgreens-jobs-install-data8x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 43200; dataset_multiplier = 8; meta.timeout = 43200; };
 
 
  });

in jobs
