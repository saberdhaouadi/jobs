{ builds ? import ../. {},
  platform ? ((import <config> {}).getLB (import ../lb-version.nix)),
  paperboat ? null
}:
(import <nixpkgs> {}).lib.overrideDerivation (

import <nixpkgs/nixos/tests/make-test.nix> ({ pkgs, lib, ... }:
let
  builder_config = import <config> {};

  elasticmq = pkgs.fetchurl {
    url = "https://s3-eu-west-1.amazonaws.com/softwaremill-public/elasticmq-server-0.13.8.jar";
    sha256 = "1qb93r97ndplp230vfzw3hfr188617p1n8alpgj4aqgk86hmylj1";
  };

  # fake AWS creds for the AWS cli to use
  awsAccessKey = "9NLZKB4SPH2OP5L845XE";
  awsSecretKey = "rvzui7pQS0PI1aAOhtTHWVmJvhMY+b9xSw7arAbC";

  awsEnvironment = {
    AWS_ACCESS_KEY_ID=awsAccessKey;
    AWS_SECRET_ACCESS_KEY=awsSecretKey;
    AWS_REGION="us-east-1";
  };

  common =
    { config, nodes, pkgs, lib, ... }:
    {
      options = {
        deployment = lib.mkOption {
          internal = true;
          default = {};
          description = ''
            Attribute set of derivations used to setup the system.
          '';
        };
      };
      config = {
        networking.firewall.enable = false;

        networking.extraHosts = ''
          ${(lib.head nodes.aws.config.networking.interfaces.eth1.ipv4.addresses).address} lb-jobs.aws
        '';

        environment.systemPackages = with pkgs; [ awscli jq curl openssl ];
        environment.shellInit = ''
          export AWS_ACCESS_KEY_ID=${awsAccessKey}
          export AWS_SECRET_ACCESS_KEY=${awsSecretKey}
          export AWS_REGION=us-east-1
        '';

        # pass some global info
        system.build.s3Name = "lb-jobs";
      };
    };

  clientConfig = pkgs.writeText "lb-steve-client.config" ''
    s3_endpoint = http://192.168.1.1:9000
    default_input_prefix = s3://lb-jobs/inputs
    default_output_prefix = s3://lb-jobs/outputs

    service = http://frontend:8081/job

    [auth]
    user = user1
    key_file = ${./keys/dummy-lb-jobs-key.pem}
  '';
in
{
  name = "lb-jobs-tests";
  nodes = {
    aws =
      { config, pkgs, ...}:
      {
        imports = [ common ];

        virtualisation.diskSize = 4096;

        environment.etc."elastiqmq/custom.conf".text = ''
          include classpath("application.conf")

          // What is the outside visible address of this ElasticMQ node
          // Used to create the queue URL (may be different from bind address!)
          node-address {
              protocol = http
              host = aws
              port = 9324
              context-path = ""
          }

          rest-sqs {
              enabled = true
              bind-port = 9324
              bind-hostname = "0.0.0.0"
              // Possible values: relaxed, strict
              sqs-limits = strict
          }

          // Should the node-address be generated from the bind port/hostname
          // Set this to true e.g. when assigning port automatically by using port 0.
          generate-node-address = false

          queues {
              lb-jobs-status {
                  defaultVisibilityTimeout = 10 seconds
                  delay = 5 seconds
                  receiveMessageWait = 0 seconds
              }
              lb-jobs-worker {
                  defaultVisibilityTimeout = 10 seconds
                  delay = 5 seconds
                  receiveMessageWait = 0 seconds
              }
          }
        '';

        systemd.services.minio-s3 =
          { config, ...}:
          {
            environment = {
              MINIO_ACCESS_KEY=awsAccessKey;
              MINIO_SECRET_KEY=awsSecretKey;
            };
            wantedBy = [ "multi-user.target" ];
            script = ''
              ${pkgs.minio}/bin/minio server aws-s3 --config-dir .
            '';
          };

        systemd.services.elasticmq-server =
          { config, ...}:
          {
            wantedBy = [ "multi-user.target" ];
            path = [ pkgs.openjdk ];
            script = ''
              set -x
              java -Dconfig.file=/etc/elastiqmq/custom.conf -jar ${elasticmq}
            '';
          };
      };

    worker =
      { config, pkgs, ... }:
      {
        imports = [ common ../nix/worker.nix ];
        virtualisation.writableStore = true;
        virtualisation.memorySize = 6*1024;
        virtualisation.diskSize = 8192;

        deployment.targetEnv = "worker";

        boot.kernel.sysctl."vm.panic_on_oom" = 0;

        logicblox.jobs.builds = builds;

        systemd.services.lb-steve-worker.environment = awsEnvironment;

        system.activationScripts.ec2metadata = ''
          touch /root/user-data

          echo "${toString [
            pkgs.pythonFull
            pkgs.pythonPackages.pandas
            pkgs.pythonPackages.scikitlearn
            pkgs.pythonPackages.matplotlib
            pkgs.pythonPackages.plotly
            pkgs.pythonPackages.statsmodels
            pkgs.socat
            pkgs.jq
            pkgs.curl
            pkgs.perl
            pkgs.fio
            pkgs.time
            (builder_config.getLB "4.4.6.1")
          ]}"
        '';

        lb-steve-worker.arguments = "--incoming http://aws:9324/queue/lb-jobs-worker --outgoing http://aws:9324/queue/lb-jobs-status --bucket ${config.system.build.s3Name} --key-service http://keyserver:8082/keys --s3-endpoint http://192.168.1.1:9000";
      };

    frontend =
      { config, pkgs, ... }:
      {
        virtualisation.memorySize = 2*1024;

        imports = [ common ../nix/frontend.nix ];
        logicblox.jobs.builds = builds;

        systemd.services.lb-steve-frontend.environment = awsEnvironment;

        system.build.frontendConfig = pkgs.writeText "lb-steve-frontend.config" ''
          [global]
          jvm_dump_dir = /tmp
          logdir_access = /var/log/lb-steve-worker
          logdir = /var/log/lb-steve-worker
          authentication_cache = $(LB_DEPLOYMENT_HOME)/authentication_cache
          tmpdir = /tmp
          http_server_threads = 500

          [handler:steve]
          database_prefix = http://database:8080/db
          s3_endpoint = http://192.168.1.1:9000

          [job-queue:worker]
          implementation = sqs
          env_credentials = true
          sqs_endpoint = http://aws:9324
          sqs_queue_url = http://aws:9324/queue/lb-jobs-worker

          [status-queue]
          implementation = sqs
          env_credentials = true
          sqs_endpoint = http://aws:9324
          sqs_queue_url = http://aws:9324/queue/lb-jobs-status

          [job-implementations]
          prefix = s3://lb-jobs/jobs-impl

          [job-logs]
          prefix = s3://lb-jobs/jobs

          [realm-config:default-signature]
          mechanism_option_credential_service = http://database:55183/admin/credentials
        '';
      };

    keyserver =
      { config, pkgs, ... }:
      {
        imports = [ common ../nix/keyserver.nix ];
        logicblox.jobs.builds = builds;
      };

    client =
      { config, pkgs, ... }:
      {
        imports = [ common ];
        environment.systemPackages = [ pkgs.openjdk pkgs.python2 builds.client.build (builder_config.getLB "4.4.8") ];
      };

    database =
      { config, pkgs, lib, ... }:
      {
        imports = [ common ../nix/database.nix ];
        logicblox.jobs.builds = builds;
        logicblox.jobs.platform = platform;
        systemd.services.lb-web-server.environment = awsEnvironment;
        virtualisation.memorySize = 4096;
        virtualisation.diskSize = 8192;
      };
  };
  testScript = ''
    subtest "Initializing", sub {
      $aws->start;
      $aws->waitForUnit("elasticmq-server");
      $aws->waitForUnit("minio-s3");

      $database->start;
      $database->waitForUnit("install-app");

      $frontend->start;
      $frontend->waitForUnit("lb-steve-frontend");

      $keyserver->start;
      $keyserver->waitForUnit("lb-steve-key-server");

      startAll;
      $worker->waitForUnit("lb-steve-worker");

      # initialize users
      $database->succeed("lb web-client import -i ${./data/users.csv} http://localhost:8080/tdx/users");
      $database->succeed("lb web-client import -i ${./data/provision-config.csv} http://localhost:8080/tdx/provision-config");
      $database->succeed("lb web-client import -i ${./data/platform_versions.csv} http://localhost:8080/tdx/platform_versions");

      # make encryption keys available in the key server
      $keyserver->succeed("mkdir -p /keys/lb-steve/logicblox/");
      $keyserver->succeed("cp ${./keys/test-key.pem} /keys/lb-steve/logicblox/test-key.pem");
    };

    subtest "Basic AWS CLI tests", sub {
      $client->succeed("aws --endpoint-url http://192.168.1.1:9000 s3api create-bucket --bucket lb-jobs");
      $client->succeed("aws --endpoint-url http://192.168.1.1:9000 s3 ls s3://lb-jobs");
      $client->succeed("aws sqs list-queues --region elasticmq --endpoint-url http://aws:9324");
    };

    subtest "Basic lb-steve CLI tests", sub {
      $client->succeed("lb-steve -c ${clientConfig} list-queues");
      $client->succeed("lb-steve -c ${clientConfig} list-platforms");
      $client->succeed("lb-steve -c ${clientConfig} list-impl");
    };

    subtest "Running identity job with encryption", sub {
      $client->succeed("mkdir .s3lib-keys; cp ${./keys/test-key.pem} .s3lib-keys/test-key.pem");
      $client->succeed("echo 'This content is encrypted' > encrypted.txt");
      $client->succeed("cloud-store upload -i encrypted.txt s3://lb-jobs/inputs/encrypted.txt --key test-key --keydir .s3lib-keys --endpoint http://192.168.1.1:9000");
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl identity -i ${../sample-jobs}/identity --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl identity --wait -m no-services=true -i s3://lb-jobs/inputs/encrypted.txt --input-key test-key --output-key test-key --output s3://lb-jobs/output/");
      $client->succeed("cloud-store download s3://lb-jobs/output/encrypted.txt --overwrite --keydir .s3lib-keys --endpoint http://192.168.1.1:9000");
      $client->succeed("[[ \$(cat ./encrypted.txt) = 'This content is encrypted' ]]");
    };

    ${lib.concatMapStrings (i: ''
    subtest "Running '${i}' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl ${i} -i ${../sample-jobs}/${i} --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl ${i} --wait");
    };'') [ "noop" "gurobi" "total" "ancestor" ]}

    subtest "lwfm training test", sub {
      $client->succeed("aws s3 cp ${<paperboat>}/foula-*.tgz s3://lb-jobs/paperboat/foula.tgz --endpoint-url http://192.168.1.1:9000");
      $client->succeed("aws s3 cp ${./data/lwfm-training}  s3://lb-jobs/paperboat/${builtins.baseNameOf ./data/lwfm-training} --endpoint-url http://192.168.1.1:9000");
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl lwfm -i ${../sample-jobs}/lwfm --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl lwfm -i s3://lb-jobs/paperboat/foula.tgz -i s3://lb-jobs/paperboat/${builtins.baseNameOf ./data/lwfm-training} --wait -m no-services=true");
    };

    subtest "Running 'metadata' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl metadata -i ${../sample-jobs}/metadata --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl metadata --wait -m key=value -m no-services=true");
    };

    subtest "Running 'identity' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl identity -i ${../sample-jobs}/identity --wait");
      $client->succeed("echo '123' > asd1.txt");
      $client->succeed("echo '456' > asd2.txt");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl identity --wait -m no-services=true -i asd1.txt -i asd2.txt -o output");
      $client->succeed("diff asd1.txt output/asd1.txt");
      $client->succeed("diff asd2.txt output/asd2.txt");
    };

    subtest "Running 'fail' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl fail -i ${../sample-jobs}/fail --wait");
      $client->fail("lb-steve -c ${clientConfig} create-job --impl fail --wait");
    };

    subtest "Running 'oom-killer' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl oom-killer -i ${../sample-jobs}/oom-killer --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl oom-killer --wait | grep 'Job was killed, most likely due to memory shortage'");
    };

    subtest "Running 'no-network' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl no-network -i ${../sample-jobs}/no-network --wait -m no-services=true");
      $client->fail("lb-steve -c ${clientConfig} create-job --impl no-network --wait");
    };

    subtest "Running 'timeout' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl timeout -i ${../sample-jobs}/timeout --wait");
      $client->fail("lb-steve -c ${clientConfig} create-job --impl timeout --wait -m timeout=30");
    };

    subtest "Running 'r-test' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl r-test -i ${../sample-jobs}/r-test --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl r-test --wait -m no-services=true -m dependencies=R,rPackages.nlme,rPackages.data_table");
    };

    subtest "Running 'scikitlearn' job", sub {
      $client->succeed("lb-steve -c ${clientConfig} upload-impl --impl scikitlearn -i ${../sample-jobs}/scikitlearn --wait");
      $client->succeed("lb-steve -c ${clientConfig} create-job --impl scikitlearn --wait -m no-services=true -m dependencies=pythonPackages.matplotlib,pythonPackages.numpy,pythonPackages.scikitlearn");
    };
  '';
}) {}
) (drv: { __noChroot = true; inherit (drv) driver; requiredSystemFeatures = [ "perf" ]; })
