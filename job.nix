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


  bench = name: precommand: command: id: attrs:
    let
      heap_profiling = true;
      bt = with pkgs; callPackage "${benchmarks}/benchmark-tools" {};
    in builder_config.buildLB (attrs // {
      inherit name;
      buildInputs = [ logicblox bt pkgs.bc pkgs.gperftools pkgs.binutils pkgs.ghostscript pkgs.graphviz pkgs.perl ];
      requiredSystemFeatures = ["perf"];
      LB_CONFIG = ./config/perf;
      buildCommand = ''
        function record_span()
        {
          local id="$1"
          shift

          local t1="$(date +%s.%N)"
          "$@"
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

        pushd $LB_DEPLOYMENT_HOME
        mkdir exports
        pushd exports
        tar xvf ${data}
        ln -s 20160608-083904 latest
        popd
        popd

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

        for f in $(find $out/report -name "*.pdf"); do
          echo "file pdf" $f >> $out/nix-support/hydra-build-products
        done
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

  } // ( pkgs.lib.optionalAttrs (benchmarks != null) {
/*
    benchmark.various =
      builder_config.buildLB {
        name = "lb-jobs-metrics-various";
        buildInputs = [ logicblox pkgs.bc pkgs.binutils ];
        requiredSystemFeatures = ["perf"];
        buildCommand = ''
          mkdir -p $LB_DEPLOYMENT_HOME/config
          cat > $LB_DEPLOYMENT_HOME/config/lb-server.config <<EOF
          [workspace]
          auto_backup_mode=none
          EOF

          set -x
          tar xvf ${data}
          mv 20160608-083904 single

          files="users jobimpls jobimpl_metadata jobs job_metadata job_inputs job_outputs job_status provision-config platform_versions"

          # shuffle and partition the files
          mkdir tmp partitioned
          for f in $files; do
            tail -n +2 single/$f.csv > tmp/$f.csv
            shuf tmp/$f.csv > tmp/shuf.csv
            split -n l/10 tmp/shuf.csv tmp/$f
            rm tmp/$f.csv tmp/shuf.csv
            for p in tmp/$f*; do
              head -1 single/$f.csv > partitioned/$(basename $p)
              cat $p >> partitioned/$(basename $p)
              rm $p
            done
          done

          for datadir in single; do
            # always load data with enough memory
            export LB_MEM=32G
            lb server stop
            lb server start

            lb delete lb-steve || echo "okay"
            ${jobs.database.build}/install.sh

            # load the csv files type by type, and supporting partitioned files.
            for t in $files; do
              for f in $datadir/$t*; do
                lb web-client import --timeout 3600 -n -i file://$PWD/$f http://localhost:8080/tdx/$t
              done
            done

            for mem in 500M 1G 2G 4G 8G 16G; do
              export LB_MEM=$mem
              lb server stop
              lb server start

              for c in 1 2 3 4 5 10 20; do
                echo '{}' > post.json
                local t1="$(date +%s.%N)"
                ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c $c -n 1000 http://localhost:55183/metrics
                local t2="$(date +%s.%N)"
                local t3="$(echo "$t2 - $t1" | bc)"
                echo "$datadir,metrics,$mem,$c,$t3" >> results.csv
              done
            done
          done

          mkdir -p $out
          mkdir -p $out/nix-support

          cp results.csv $out
          echo "file data $out/results.csv" >> $out/nix-support/hydra-build-products
        '';
      };
*/

    benchmark.load-data =
      bench "lb-jobs-install-with-data" "${jobs.database.build}/install.sh" "" "load" {};

    benchmark.get-metrics-c2 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-600s" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -s 60 -c 2 -t 1800 http://localhost:55183/metrics
      '' "metrics" {};

    benchmark.get-metrics-2G-c2 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-600s" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -s 60 -c 2 -t 1800 http://localhost:55183/metrics
      '' "metrics" { LB_MEM="2G"; };

/*
    benchmark.get-metrics-c10 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 10 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 10 -n 10000 http://localhost:55183/metrics
      '' "metrics" {};

    benchmark.get-metrics-2G-c10 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 10 -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -c 10 -n 10000 http://localhost:55183/metrics
      '' "metrics" { LB_MEM="2G"; };

    benchmark.get-metrics-c1 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -n 10000 http://localhost:55183/metrics
      '' "metrics" {};

    benchmark.get-metrics-2G-c1 =
      bench "lb-jobs-metrics-call" "${jobs.database.build}/install.sh" ''
        echo '{}' > post.json
        record_span "get-metrics-1000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -n 1000 http://localhost:55183/metrics
        record_span "get-metrics-10000" ${pkgs.apacheHttpd}/bin/ab -T application/json -p post.json -n 10000 http://localhost:55183/metrics
      '' "metrics" { LB_MEM="2G"; };
*/
  });


in jobs
