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
, bench_duration ? 30*60
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
      hp_enabled = ((attrs ? heap_profiling) && attrs.heap_profiling);
    in builder_config.buildLB (attrs // {
      inherit name;
      buildInputs = [ logicblox lb_web bt pkgs.bc pkgs.gperftools pkgs.binutils pkgs.ghostscript pkgs.graphviz pkgs.perl pkgs.pythonPackages.requests2 ] ++ (attrs.buildInputs or []);
      requiredSystemFeatures = ["perf"];
      # LB_CONFIG = ./config/perf;
      buildCommand = ''
        function record_span()
        {
          local id="$1"
          shift

          local t1="$(date +%s.%N)"
          ${pkgs.lib.optionalString (attrs ? timeout) "timeout ${toString attrs.timeout}"} "$@" ${pkgs.lib.optionalString (attrs ? timeout) "|| true"}
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
          cat wssize-monitor.csv >> $id-report/logs/wssize-monitor.csv

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
          cp $id-report/logs/wssize-monitor.csv $out/report/$id-wssize-monitor.csv
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
            head -1 $c.csv > tmp-$c.csv
            for i in $(seq $dataset_multiplier); do
              # Update job ID (1st column)
              tail -n +2 $c.csv | sed "s/^\"\([^\"]*\)\"|/\"\1-$i\"|/" > $c-$i.csv
              if [ "$c" = "jobs" ]; then
                # CLIENTID (5th column) should be unique too
                sed -i "s/\"\([^\"]*\)\"/\"\1-$i\"/5" $c-$i.csv
              fi
              cat $c-$i.csv >> tmp-$c.csv
              rm $c-$i.csv
            done
            mv tmp-$c.csv $c.csv
          done
        ''}
        popd
        popd

        ${precommand}

        profile_interval=150 # sec.

        function start_lb_server() {
          local hp_prefix="$1"
          ${if hp_enabled
            then ''
              # Enable heap-profiling for throughput phase
              mkdir -p hprof
              echo "Launching lb-server under heap-profiler"
              LD_LIBRARY_PATH=${pkgs.glibc}/lib \
              LD_PRELOAD=${pkgs.gperftools}/lib/libtcmalloc.so \
              HEAP_PROFILE_ALLOCATION_INTERVAL=0 \
              HEAP_PROFILE_DEALLOCATION_INTERVAL=0 \
              HEAP_PROFILE_INUSE_INTERVAL=0 \
              HEAP_PROFILE_TIME_INTERVAL=$profile_interval \
              HEAPPROFILE=hprof/$hp_prefix \
                lb-server --daemonize false &
            ''
            else ''
              lb server start
            ''}
            sleep 60
        }

        function stop_lb_server() {
          lb server stop || true
          pkill -f 'lb-server' || true
          sleep 5
        }

        function restart_services() {
          local hp_prefix="$1"
          stop_monitors
          sleep 2
          stop_lb_server
          lb services restart
          lb server stop
          start_lb_server $hp_prefix
          sleep 5
          start_monitors
        }

        function rebuild_workspace() {
          local hp_prefix="$1"
          echo "Rebuilding workspace"
          print_last_profile $hp_prefix
          lb delete lb-steve || true
          # Make sure we get a dump between workspace deletion & rebuild
          sleep $((profile_interval + 60))
          print_last_profile $hp_prefix
          ${jobs.database.build}/install.sh
        }

        function start_monitors() {
          ${if hp_enabled
            then "lb_server_pid=$(pgrep -f 'lb-server --daemonize')"
            else "lb_server_pid=$(cat $LB_DEPLOYMENT_HOME/logs/current/lb-server.pid)"}

          iousg-monitor  --iousg-pid  $lb_server_pid  >> iousg-monitor.csv  2>&1 &
          cpuusg-monitor --cpuusg-pid $lb_server_pid  >> cpuusg-monitor.csv 2>&1 &
          memusg-monitor --memusg-pid $lb_server_pid  >> memusg-monitor.csv 2>&1 &
          # inverval == HEAP_PROFILE_TIME_INTERVAL
          wssize-monitor --interval $profile_interval >> wssize-monitor.csv 2>&1 &
          # cmd-monitor --cmd "lb batch-script lb-steve 'profileDiskSpace -T -S -L -O -V -K'" --mode create-new --file profileDiskSpace.out --interval 1800 &
        }

        function stop_monitors() {
          pkill -f iousg-monitor  || true
          pkill -f cpuusg-monitor || true
          pkill -f memusg-monitor || true
          pkill -f wssize-monitor || true
          pkill -f cmd-monitor    || true
        }

        function generate_heap_profiles() {
          local hp_prefix="$1"
          ${pkgs.lib.optionalString hp_enabled ''
            pushd hprof

            set +o pipefail
            ls -l
            nhprofs=$(find . -maxdepth 1 -name "$hp_prefix.*.heap" | wc -l)
            t1_prof=$(ls $hp_prefix.*.heap | head -n 6 | tail -n 1)
            t2_prof=$(ls $hp_prefix.*.heap | head -n $((nhprofs / 2)) | tail -n 1)
            t3_prof=$(ls $hp_prefix.*.heap | tail -n 2 | head -n 1)
            set -o pipefail

            if [ $nhprofs -gt 5 ]; then
              step=5
            else
              step=1
            fi
            # for i in $(seq 1 $step $nhprofs)
            for i in $(seq 1 1 $nhprofs)
            do
              fn="$hp_prefix.$(printf %04d $i).heap"
              pprof --pdf  $LOGICBLOX_HOME/bin/lb-server $fn > $out/report/$fn.pdf
              pprof --text $LOGICBLOX_HOME/bin/lb-server $fn > $out/report/$fn.txt
              gzip $out/report/$fn.txt
              pprof --pdf --alloc_space $LOGICBLOX_HOME/bin/lb-server $fn > $out/report/$fn-alloc.pdf
            done

            if [ $nhprofs -gt 25 ]; then
              step=25
            else
              step=2
            fi
            prev=1
            for i in $(seq $step $step $nhprofs)
            do
              fn_prev="$hp_prefix.$(printf %04d $prev).heap"
              fn="$hp_prefix.$(printf %04d $i).heap"

              pprof --pdf --base=$fn_prev $LOGICBLOX_HOME/bin/lb-server $fn > $out/report/$hp_prefix-diff-$prev-$i.pdf || true
              pprof --pdf --alloc_space --base=$fn_prev $LOGICBLOX_HOME/bin/lb-server $fn > $out/report/$hp_prefix-diff-alloc-$prev-$i.pdf || true
            
              prev=$i
            done

            pprof --pdf --base=$t1_prof $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/$hp_prefix-diff-1.pdf || true
            pprof --pdf --alloc_space --base=$t1_prof $LOGICBLOX_HOME/bin/lb-server $t2_prof > $out/report/$hp_prefix-diff-alloc-1.pdf || true
            pprof --pdf --base=$t2_prof $LOGICBLOX_HOME/bin/lb-server $t3_prof > $out/report/$hp_prefix-diff-2.pdf || true
            pprof --pdf --alloc_space --base=$t2_prof $LOGICBLOX_HOME/bin/lb-server $t3_prof > $out/report/$hp_prefix-diff-alloc-2.pdf || true

            popd
          ''}
        }

        function print_last_profile() {
          local hp_prefix="$1"
          ${pkgs.lib.optionalString hp_enabled ''
            pushd hprof
            echo "$(ls $hp_prefix.*.heap | tail -n 1)"
            popd
          ''}
        }

        mkdir -p $out/report

        ${command}

        fancy_report ${id}

        # mkdir $out/report/profileDiskSpace
        # cp profileDiskSpace.out* $out/report/profileDiskSpace

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
        buildInputs = [ logicblox lb_web ];
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
/*
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
 
    benchmark.dev-walgreens-jobs-data2x-3h =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311} 
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 10800; dataset_multiplier = 2; meta.timeout = 36000; meta.maxSilent = 36000; };

    benchmark.dev-walgreens-jobs-run =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311}
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 7200; meta.timeout = 18000; };

    benchmark.dev-walgreens-jobs-run-data2x-4h =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311} 
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 14400; dataset_multiplier = 2; meta.timeout = 36000; meta.maxSilent = 36000; };

    benchmark.dev-walgreens-jobs-run-data2x-12h =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311} 
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 12*60*60; dataset_multiplier = 2; meta.timeout = 20*60*60; meta.maxSilent = 20*60*60; };
*/

    benchmark.dev-walgreens-jobs-run-data2x-nohp =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        restart_services "lb-server.hprof"
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311} 
      '' "metrics" { buildInputs = [ mitmproxy ];
                     dataset_multiplier = 2;
                     timeout = bench_duration;
                     heap_profiling = false;
                     meta.timeout = 50*60*60;
                     meta.maxSilent = 50*60*60; };

/*
    benchmark.dev-walgreens-jobs-run-data2x-hp =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        restart_services "lb-server.hprof"
        record_span "lb-walgreens-jobs" mitmdump -nc ${requests_dev_20170303-101311}
        generate_heap_profiles "lb-server.hprof"
      '' "metrics" { buildInputs = [ mitmproxy ];
                     dataset_multiplier = 2;
                     timeout = bench_duration;
                     heap_profiling = true;
                     meta.timeout = 50*60*60;
                     meta.maxSilent = 50*60*60; };

    benchmark.dev-walgreens-jobs-run-data2x-restarts =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "${jobs.database.build}/install.sh" ''
        mitmdump --version
        # mitmdump -nr ${requests_dev_20170303-101311} -s "${./split.py} requests.part 47000"
        mitmdump -nr ${requests_dev_20170303-101311} -s "${./split.py} requests.part 4800"

        restart_services "lb-server.hprof.0"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.0
        generate_heap_profiles "lb-server.hprof.0"

        restart_services "lb-server.hprof.1"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.1
        generate_heap_profiles "lb-server.hprof.1"

        restart_services "lb-server.hprof.2"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.2
        generate_heap_profiles "lb-server.hprof.2"
      '' "metrics" { buildInputs = [ mitmproxy ]; dataset_multiplier = 2; meta.timeout = 50*60*60; meta.maxSilent = 50*60*60; };

    benchmark.dev-walgreens-jobs-run-data2x-rebuilds =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        mitmdump --version
        # mitmdump -nr ${requests_dev_20170303-101311} -s "${./split.py} requests.part 47000"
        # mitmdump -nr ${requests_dev_20170303-101311} -s "${./split.py} requests.part 2400"
        mitmdump -nr ${requests_dev_20170303-101311} -s "${./split.py} requests.part 100"

        restart_services "lb-server.hprof"
        rebuild_workspace "lb-server.hprof"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.0

        rebuild_workspace "lb-server.hprof"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.0

        rebuild_workspace "lb-server.hprof"
        record_span "lb-walgreens-jobs" mitmdump -nc requests.part.0
        generate_heap_profiles "lb-server.hprof"
      '' "metrics" { buildInputs = [ mitmproxy ]; meta.timeout = 20*60*60; meta.maxSilent = 20*60*60; };
      # '' "metrics" { buildInputs = [ mitmproxy ]; dataset_multiplier = 2; meta.timeout = 20*60*60; meta.maxSilent = 20*60*60; };

    benchmark.dev-walgreens-jobs-install =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 7200; meta.timeout = 18000; };
 
    benchmark.dev-walgreens-jobs-install-data2x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 36000; dataset_multiplier = 2; meta.timeout = 36000; meta.maxSilent = 36000; };

    benchmark.dev-walgreens-jobs-install-data4x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 36000; dataset_multiplier = 4; meta.timeout = 36000; meta.maxSilent = 36000; };

    benchmark.dev-walgreens-jobs-install-data8x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 43200; dataset_multiplier = 8; meta.timeout = 43200; meta.maxSilent = 43200; };
 
    benchmark.dev-walgreens-jobs-install-data32x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 43200; dataset_multiplier = 32; meta.timeout = 43200; };

    benchmark.dev-walgreens-jobs-install-data64x =
      bench data_dev_20170303-101311 "lb-walgreens-jobs-run" "" ''
        record_span "run-installer" ${jobs.database.build}/install.sh
      '' "metrics" { buildInputs = [ mitmproxy ]; timeout = 43200; dataset_multiplier = 64; meta.timeout = 43200; };
*/ 
  });

in jobs
