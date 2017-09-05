import <nixpkgs/nixos/tests/make-test.nix> ({ pkgs, lib, ... }:
let
  elasticmq = pkgs.fetchurl {
    url = "https://s3-eu-west-1.amazonaws.com/softwaremill-public/elasticmq-server-0.13.8.jar";
    sha256 = "1qb93r97ndplp230vfzw3hfr188617p1n8alpgj4aqgk86hmylj1";
  };
  minio = pkgs.buildGoPackage rec {
    name = "minio";
    goPackagePath = "github.com/minio/minio";
    rev = "e2aba9196f849c458303aff42d2d6ea3e3ea8904";

    src = pkgs.fetchgit {
      inherit rev;
      url = "https://github.com/minio/minio.git";
      sha256 = "1iixpxcyhfa1lln3qd4xpnmjpbkf0zicj1irk21wqjqkac3rar0s";
    };
  };
  # fake AWS creds for the AWS cli to use
  awsAccessKey = "9NLZKB4SPH2OP5L845XE";
  awsSecretKey = "rvzui7pQS0PI1aAOhtTHWVmJvhMY+b9xSw7arAbC";

  builds = import ../. {};

  common =
    { config, pkgs, lib, ... }:
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
        environment.systemPackages = [ pkgs.awscli ];
        environment.shellInit = ''
          export AWS_ACCESS_KEY_ID=${awsAccessKey}
          export AWS_SECRET_ACCESS_KEY=${awsSecretKey}
        '';
      };
    };
in
{
  name = "lb-jobs-tests";
  nodes = {
    aws =
      { config, pkgs, ...}:
      {
        imports = [ common ];

        environment.etc."elastiqmq/custom.conf".text = ''
          include classpath("application.conf")

          // What is the outside visible address of this ElasticMQ node 
          // Used to create the queue URL (may be different from bind address!)
          node-address {
              protocol = http
              host = localhost
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
              steve-jobs-status {
                  defaultVisibilityTimeout = 10 seconds
                  delay = 5 seconds
                  receiveMessageWait = 0 seconds
              }
              steve-jobs-worker {
                  defaultVisibilityTimeout = 10 seconds
                  delay = 5 seconds
                  receiveMessageWait = 0 seconds
              }
          }
        '';

        networking.firewall.allowPing = true;
        networking.firewall.allowedTCPPorts = [ 9324 ];

        systemd.services.minio-s3 =
          { config, ...}:
          {
            environment = {
              MINIO_ACCESS_KEY=awsAccessKey;
              MINIO_SECRET_KEY=awsSecretKey;
            };
            wantedBy = [ "multi-user.target" ];
            script = ''
              mkdir -p aws-s3/steve-jobs
              ${minio}/bin/minio server aws-s3
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
        imports = [ common ];
      };

    frontend =
      { config, pkgs, ... }:
      {
        imports = [ common ];
      };

    keyserver =
      { config, pkgs, ... }:
      {
        imports = [ common ];
      };

    client =
      { config, pkgs, ... }:
      {
        imports = [ common ];
        environment.systemPackages = [ pkgs.openjdk pkgs.python2 builds.client.build ];
      };

    database =
      { config, pkgs, ... }:
      {
        imports = [ common ../nix/database.nix ];
        virtualisation.memorySize = 4096;
        system.build.s3Name = "steve-jobs";
      };
  };
  testScript = ''
    $aws->start;
    $aws->waitForUnit("elasticmq-server");
    $aws->waitForUnit("minio-s3");

    $database->start;
    $database->waitForUnit("install-app");

    $frontend->start;
    #$frontend->waitForUnit("lb-steve-frontend");

    $keyserver->start;
    #$keyserver->waitForUnit("lb-steve-key-server");

    startAll;
    print $aws->succeed("aws --endpoint-url http://127.0.0.1:9000 s3 ls s3://steve-jobs");
    print $aws->succeed("aws sqs list-queues --region elasticmq --endpoint-url http://127.0.0.1:9324");
    print $worker->succeed("aws sqs list-queues --region elasticmq --endpoint-url http://aws:9324");
  '';
})
