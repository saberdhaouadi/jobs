{ region ? "us-east-1"
, account ? "lb-jobs"
, accountId ? "826045886586"
, accessKey
, name
, logToken ? ""
, sumoToken ? ""
, vpcId ? ""
, subnetId ? ""
, production ? false
, allowedGroups ? [ "admins" ]
, gcpProject                     # (required) GCE project to deploy to
, serviceAccount ? "lb-jobs-dev@lb-jobs.iam.gserviceaccount.com" # (required) GCE service account email
, latestLb ? true
, provisionVpc ? false
, ...
}:
let
  environments = import ./environments.nix;
  env = environments."${name}";

  instanceTypes = builtins.attrNames env.workers;

  amis = if production then import ./prod-amis.nix else import ./amis.nix ;
  bootstrap-images = import ./bootstrap-images.nix;

  devips = import ./dev-ips.nix;
  prodips = import ./prod-ips.nix;
  natips = import ./nat-ips.nix;
  prodnatips = import ./prod-nat-ips.nix;

  dep-region = env.region;
  google-nat-ip = env.google-nat-elastic-ip;

  workerName = type : pkgs.lib.replaceChars ["."] ["-"] type;
  sqsName = type : "steve-jobs-${name}-${pkgs.lib.replaceChars ["."] ["-"] type}";
  sqsStatusName = "steve-jobs-${name}-status";
  sqsQueue = type: { inherit region ; accessKeyId = account; visibilityTimeout = 1800; name = sqsName type;};
  sqsStatusQueue = { inherit region ; accessKeyId = account; name = sqsStatusName; };
  sqsURL = type: "https://sqs.${region}.amazonaws.com/${accountId}/${sqsName type}";
  sqsStatusURL = "https://sqs.${region}.amazonaws.com/${accountId}/${sqsStatusName}";
  sqsQueues = with pkgs.lib; listToAttrs (map (n: nameValuePair (sqsName n) (sqsQueue n)) instanceTypes) ;

  pkgs = import <nixpkgs> { config.allowUnfree = true; config.allowBroken = true; };
  builder-config = import <config> {};
  inherit (pkgs.lib) getAttr;

  cloudwatchpolicy = ''
    { "Action": [ "cloudwatch:PutMetricData",
                  "cloudwatch:GetMetricStatistics",
                  "cloudwatch:ListMetrics",
                  "ec2:DescribeTags"],
      "Effect": "Allow",
      "Resource": "*"
    }
   '';
  SESpolicy = ''
    { "Effect": "Allow",
      "Action": [ "ses:*" ],
      "Resource": "*"
    }
   '';

  instanceProfileArn = name: "arn:aws:iam::${accountId}:instance-profile/${name}";

  profiler = with pkgs; stdenv.mkDerivation {
    name = "lightweight-java-profiler";
    src = fetchsvn {
      url = "http://lightweight-java-profiler.googlecode.com/svn/trunk";
      rev = 12;
      sha256 = "06mc3hwv83w9cbajqw247swifxz2kjgikswgkk9a0qzpjkx06lpk";
    };
    buildInputs = [ jdk ];
    preConfigure = ''
      sed -i 's|32|64|' Makefile
      sed -i 's|"PRIdPTR"|" PRIdPTR "|' src/display.cc
    '';
    installPhase = ''
      mkdir -p $out/lib
      cp build-64/liblagent.so $out/lib
    '';
  };


  worker = queue: type:
    { config, pkgs, resources, nodes, lib, ... }:
    {
      imports = [ ./worker.nix ];
      boot.kernelPackages = pkgs.linuxPackages_4_9;

      lb-steve-worker.arguments = "--incoming ${resources.sqsQueues."${sqsName queue}".name} --outgoing ${resources.sqsQueues."${sqsStatusName}".name} --bucket ${s3Name} --key-service https://${nodes."key-server-${name}".config.networking.privateIPv4}/keys";

      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = type;
      deployment.ec2.instanceProfile = resources.iamRoles.worker-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 100;

      # Tags are needed, so provisioner sees running worker instances for this
      # deployments, and does not start new instances if not necessary.
      deployment.ec2.tags.S3Bucket = s3Name;
      deployment.ec2.tags.IncomingQueue = sqsURL queue;
      deployment.ec2.tags.OutgoingQueue = sqsStatusURL;

      users.extraUsers.root.openssh.authorizedKeys.keys = [
      "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCunr4txUxeXVeaEkLm06vjFceW71ciwf3vPtGQNRPa3mRIxWxRvtaSXj8djNn9g9Lc/Rqjhz2LuGfi9rQVeynpglmicSmt6Ge3UpQL+Z4QibY95movUTb+yvjIFTOHGbeRBGholpfvCK1vd/ZCzv9/21X2Mbg8N1X2/pxGdsmtv6dG9tOuF4Bv47uZA4pzMUC16XxriJN9WKBcrUwv5tPqP0uQoSWnnuU/RIMnZIiZUxi16jKTdMWRUFjx69s/lHkgUdnkAim7ZahhWOCsFAQTq65RdNsi40c/6N7MenWIWWiPIqQ59VpV7E9sxXa4Kbj7W/v4wqEzTcOFuG3EHuGx ahmed.samti@infor.com"
      ];

    };

  builds = import ../. { platform_release = builder-config.getLB (import ../lb-version.nix); };
  s3Name = "steve-jobs-${name}";
  frontendConfig = pkgs.writeText "lb-steve-frontend.config"
    ''
      [global]
      jvm_dump_dir = /tmp
      logdir_access = /var/log/lb-steve-worker
      logdir = /var/log/lb-steve-worker
      authentication_cache = $(LB_DEPLOYMENT_HOME)/authentication_cache
      tmpdir = /tmp
      http_server_threads = 500

      [handler:steve]
      database_prefix = http://database-${name}:8080/db

      ${pkgs.lib.concatMapStrings (t: ''
      [job-queue:${workerName t}]
      implementation = sqs
      iam_role = default
      env_credentials = false
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsName t}
      ${if (t == "c3.xlarge") then "default = true" else ""}
      '') instanceTypes}

      [status-queue]
      implementation = sqs
      iam_role = default
      env_credentials = false
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsStatusName}

      [job-implementations]
      prefix = s3://${s3Name}/jobs-impl

      [job-logs]
      prefix = s3://${s3Name}/jobs

      [statsd]
      prefix = lb.web
      hostname = 127.0.0.1
      port = 8125

      [realm-config:default-signature]
      mechanism_option_credential_service = http://database-${name}:55183/admin/credentials
    '';

    getDeviceName = import <lbdevops/nixops/generic/device-name.nix>;

in
with pkgs.lib;
{
  network.description = "Steve Jobs [${name}]";
  require =
    [ <lbdevops/nixops/generic/tags.nix> ];
   # ++ (optionals provisionVpc [ ./vpc.nix ]);

  resources.ec2KeyPairs.worker-kp = { inherit region ; accessKeyId = account; };
  resources.ec2KeyPairs.worker-kp-us-west-2 = { region = "us-west-2"; accessKeyId = account; };
  resources.ec2KeyPairs.worker-kp-us-west-1 = { region = "us-west-1"; accessKeyId = account; };
  resources.ec2KeyPairs.worker-kp-us-east-1 = { region = "us-east-1"; accessKeyId = account; };

  resources.ec2KeyPairs.kp = { inherit region ; accessKeyId = account; };

  resources.sqsQueues = sqsQueues // { "${sqsStatusName}" = sqsStatusQueue;  };
  resources.s3Buckets."${s3Name}-bucket" = { inherit region ; accessKeyId = account; name = s3Name; };
  resources.s3Buckets."${s3Name}-logs-bucket" = { inherit region ; accessKeyId = account; name = "${s3Name}-logs"; };


  resources.iamRoles.worker-role =
    { resources, ... }:
    {
      accessKeyId = account;
      policy = ''
        {
          "Statement": [
            {
              "Effect": "Allow",
              "Action": [
                "ec2:TerminateInstances",
                "ec2:CreateTags"
              ],
              "Condition": {
                "ArnEquals": {
                  "ec2:InstanceProfile": "arn:aws:iam::${accountId}:instance-profile/${resources.iamRoles.worker-role.name}"
                }
              },
              "Resource": [
                "arn:aws:ec2:${region}:${accountId}:instance/*"
              ]
            },
            {
              "Effect": "Allow",
              "Action": "sts:AssumeRole",
              "Resource": "*"
            },
            {
              "Action": [
                "s3:Get*",
                "s3:Put*",
                "s3:List*"
              ],
              "Effect": "Allow",
              "Resource": [
                "*"
              ]
            },
            {
              "Action": [
                "sqs:ChangeMessageVisibility",
                "sqs:DeleteMessage",
                "sqs:ReceiveMessage",
                "sqs:SendMessage",
                "sqs:SetQueueAttributes",
                "sqs:GetQueueAttributes"
              ],
              "Effect": "Allow",
              "Resource": [
                ${pkgs.lib.concatStrings (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                '') instanceTypes)
                }
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsStatusName}".name}"
              ]
            },
            {
              "Action": [
                "sqs:ListQueues"
              ],
              "Effect": "Allow",
              "Resource": [ "*" ]
            }
          ]
        }
      '';
    };

  resources.iamRoles.keyserver-role =
     { resources, ... }:
     {
       accessKeyId = account;
       policy = ''
         {
           "Statement": [
             ${cloudwatchpolicy},
             ${SESpolicy}
           ]
         }
       '';
     };

  resources.iamRoles.database-role =
    { resources, ... }:
    {
      accessKeyId = account;
      policy = ''
        {
          "Statement": [
            {
              "Action": [
                "sqs:GetQueueAttributes"
              ],
              "Effect": "Allow",
              "Resource": [
                ${pkgs.lib.concatStrings (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                '') instanceTypes)
                }
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsStatusName}".name}"
              ]
            },
            {
              "Action": [
                "s3:Get*",
                "s3:Put*",
                "s3:List*",
                "s3:CreateMultipartUploadParts",
                "s3:ListMultipartUploadParts",
                "s3:AbortMultipartUpload"
              ],
              "Effect": "Allow",
              "Resource": [
                "arn:aws:s3:::${s3Name}",
                "arn:aws:s3:::${s3Name}/*"
              ]
            },
            ${cloudwatchpolicy},
            ${SESpolicy}
          ]
        }
      '';
    };

  resources.iamRoles.provisioner-role =
    { resources, ... }:
    {
      accessKeyId = account;
      policy = ''
        {
          "Statement": [
            {
              "Action": [
                "sqs:GetQueueAttributes"
              ],
              "Effect": "Allow",
              "Resource": [
                ${pkgs.lib.concatStrings (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                '') instanceTypes)
                }
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsStatusName}".name}"
              ]
            },
            {
              "Action": [
                "ec2:Describe*",
                "ec2:RunInstances",
                "ec2:TerminateInstances",
                "ec2:RequestSpotInstances",
                "ec2:CreateTags",
                "ec2:RequestSpotFleet",
                "ec2:DescribeSpotFleetRequests",
                "ec2:CancelSpotFleetRequests",
                "ec2:DescribeSpotFleetInstances",
                "ec2:DescribeSpotFleetRequestHistory",
                "ec2:ModifySpotFleetRequest",
                "ec2:CreateLaunchTemplateVersion",
                "ec2:DescribeImages",
                "ec2:DescribeInstanceStatus",
                "ec2:DescribeSubnets",
                "iam:PassRole",
                "iam:CreateServiceLinkedRole"
              ],
              "Effect": "Allow",
              "Resource": [ "*" ]
            },
            ${cloudwatchpolicy},
            ${SESpolicy}
          ]
        }
      '';
    };

  resources.iamRoles.frontend-role =
    { resources, ... }:
    {
      accessKeyId = account;
      policy = ''
        {
          "Statement": [
            {
              "Action": [
                "sqs:ChangeMessageVisibility",
                "sqs:DeleteMessage",
                "sqs:ReceiveMessage",
                "sqs:SendMessage",
                "sqs:GetQueue",
                "sqs:GetQueueUrl",
                "sqs:SetQueueAttributes",
                "sqs:GetQueueAttributes"
              ],
              "Effect": "Allow",
              "Resource": [
               ${pkgs.lib.concatStrings (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                '') instanceTypes)
                }
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsStatusName}".name}"
              ]
            },
            {
              "Action": [
                "sqs:ListQueues"
              ],
              "Effect": "Allow",
              "Resource": [ "*" ]
            },
            {
              "Action": [
                "s3:Get*",
                "s3:Put*",
                "s3:List*"
              ],
              "Effect": "Allow",
              "Resource": [ "*" ]
            },
            ${cloudwatchpolicy},
            ${SESpolicy}
          ]
        }
      '';
    };

  resources.ec2SecurityGroups.frontend-sg =
    let
      entry = ip:
        {
          fromPort = 443;
          toPort = 443;
          sourceIp = "${ip}/32";
        };
      ips = if production then prodips else devips ;
      accountEntry = account:
        {
          fromPort = 443;
          toPort = 443;
          sourceGroup.ownerId = account;
          sourceGroup.groupName = "admin";
        };
      accountEntryPort = port: sg:
        { fromPort = port;
          toPort = port;
          sourceGroup.ownerId = accountId;
          sourceGroup.groupName = sg.name;
        };
    in
      { config, resources, ... }:
      {
        inherit region;
        accessKeyId = account;
        vpcId = mkIf (vpcId != "") vpcId;
        description = "Security group for frontend";
        rules = map entry ips ++ map accountEntry (singleton accountId) ++ [ { fromPort = 55183; toPort = 55183; sourceGroup.ownerId = accountId; sourceGroup.groupName = resources.ec2SecurityGroups.frontend-sg.name; } ];
      };

    resources.ec2SecurityGroups.database-sg =
      let
        accountEntry = account:
          {
            fromPort = 8080;
            toPort = 8080;
            sourceGroup.ownerId = account;
            sourceGroup.groupName = "admin";
          };
      in
        { config, resources, ... }:
        {
          inherit region;
          accessKeyId = account;
          vpcId = mkIf (vpcId != "") vpcId;
          description = "Security group for database";
          rules = map accountEntry (singleton accountId) ++ [ { fromPort = 55183; toPort = 55183; sourceGroup.ownerId = accountId; sourceGroup.groupName = "admin"; } ];
        };

    resources.ec2SecurityGroups.key-server-nats-sg =
      let
        entry = ip:
        {
          fromPort = 443;
          toPort = 443;
          sourceIp = "${ip}/32";
        };
        ips = if production then prodnatips else natips ++ [ "34.66.55.179" ];
        accountEntry = account:
        {
          fromPort = 443;
          toPort = 443;
          sourceGroup.ownerId = account;
          sourceGroup.groupName = "admin";
        };
      in
        { config, resources, ... }:
        {
          inherit region;
          accessKeyId = account;
          vpcId = mkIf (vpcId != "") vpcId;
          description = "Security group for the key server ";
          rules = map entry ips ++ map accountEntry (singleton accountId);
        };

  "provisioner-${name}" =
    { config, resources, nodes, lib, ...}:
    let
      script = t: r: pkgs.writeScriptBin "run-provisioner-${workerName t}${lib.optionalString (r != (env.workers."${t}".defaultRegion or region)  ) "-${r}"}"
        ''
          #! /bin/sh
          source /etc/profile
          exec lb-steve-provisioner $@ \
                 --region ${r} \
                 --ami ${bootstrap-images."${r}" or (if env.workers."${t}" ? diskSize then amis."${r}".ebs else amis."${r}".s3)} \
                 --key-service https://${if r == region then nodes."key-server-${name}".config.networking.privateIPv4 else env.key-server-elastic-ip}/keys \
                 --queue ${workerName t} \
                 --bucket ${s3Name} \
                 --key ${if r == region then resources.ec2KeyPairs.worker-kp.name else (if resources.ec2KeyPairs ? "worker-kp-${r}" then resources.ec2KeyPairs."worker-kp-${r}".name else "nokey")} \
                 --incoming ${sqsURL t} \
                 --outgoing ${sqsStatusURL} \
                 --role ${resources.iamRoles.worker-role.name} \
                 --instance-type ${env.workers."${t}".instanceType or t} \
                 --security-group ${env.workers."${t}".securityGroup or "admin"} \
                 ${lib.optionalString (env.workers."${t}" ? subnetId) "--subnet-id ${env.workers."${t}".subnetId}"} \
                 --disk-size ${env.workers."${t}".diskSize or "0"} \
                 --spot-price ${env.workers."${t}".price} \
                 --percentage-spot ${env.workers."${t}".percentageSpot} \
                 --percentage-queue ${env.workers."${t}".percentageQueue or "0.6"} \
                 ${lib.optionalString (env.workers."${t}" ? maxDelta) "--max-delta ${env.workers."${t}".maxDelta}"} \
                 --max ${env.workers."${t}".max or "300"} \
                 --min ${env.workers."${t}".min or "0"}\
                 --backend ${env.workers."${t}".backend or "aws"} \
                 --project ${env.workers."${t}".project or "project"} \
                 --spotfleet-role ${env.spotfleetRole} \
                 --service-account ${env.workers."${t}".serviceAccount or "unknown"} \
                 --local-disks ${env.workers."${t}".localdisks or "2"}
        '';
      provisionScripts = lib.concatMap (  r: map (i: script i r) instanceTypes) (builtins.attrNames dep-region);
      run-provisioner = t: "${script t (env.workers."${t}".defaultRegion or region )}/bin/run-provisioner-${workerName t}";
      provisioner-service = t: {
        description = "Steve Provisioner";
        path = [ pkgs.jdk ];
        environment.GOOGLE_APPLICATION_CREDENTIALS = "/run/keys/google";
        serviceConfig = {
          ExecStart = "${run-provisioner t}";
        };
        startAt = "*:0/5";
      };

      terminate-impaired = {
        description = "Terminating impaired workers";
        path = [ pkgs.pythonFull ];
        serviceConfig = {
          ExecStart = "${./scripts/terminate-impaired} ${instanceProfileArn resources.iamRoles.worker-role.name}";
        };
        environment.PYTHONPATH = "${pkgs.pythonPackages.boto}/lib/python2.7/site-packages";
        startAt = "*:0";
      };
    in
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroupIds = [ "admin" ];
      deployment.ec2.subnetId = subnetId ;
      deployment.ec2.associatePublicIpAddress = true;
      deployment.ec2.region = region;
      deployment.ec2.instanceType = if (vpcId != "") then "r4.large" else "r3.large";
      deployment.ec2.instanceProfile = resources.iamRoles.provisioner-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 20;

      deployment.keys.google.keyFile = <global_creds/lb-jobs-gcp.json>;

      imports = [
        <lbdevops/logicblox/production.nix>
      ] ;

      environment.systemPackages = [ builds.worker pkgs.linuxPackages.sysdig ] ++ provisionScripts;
      systemd.services = listToAttrs (map (t: nameValuePair "run-provisioner-${workerName t}" (provisioner-service t) ) instanceTypes) // { inherit terminate-impaired; };
      boot.extraModulePackages = [ pkgs.linuxPackages.sysdig ];
      boot.kernelModules = [ "sysdig-probe" ] ;
    };

  "key-server-${name}" =
    { config, pkgs, resources, lib, ...}:
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroupIds = [ "admin" resources.ec2SecurityGroups.key-server-nats-sg.name ];
      deployment.ec2.subnetId = subnetId ;
      deployment.ec2.region = region;
      deployment.ec2.instanceType = if (vpcId != "") then "r4.large" else "r3.large";
      deployment.keys."server.key".text = builtins.readFile <global_creds/logicblox/server.key>;
      deployment.keys."server.crt".text = builtins.readFile <global_creds/logicblox/server.crt>;

      deployment.ec2.elasticIPv4 = env.key-server-elastic-ip;
      deployment.ec2.instanceProfile = resources.iamRoles.keyserver-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 20;
      imports = [
        <lbdevops/logicblox/production.nix>
        ./keyserver.nix
      ] ;

      # Monocle setup
      telegraf.nginxInputUrl = "http://127.0.0.1/nginx_status";

      fileSystems."/keys" =
        { autoFormat = true;
          fsType = "xfs";
          device = getDeviceName config.deployment.ec2.instanceType false; #"/dev/xvdf";
          options = [ "noatime" "_netdev" ];
          ec2.size = 20;
          ec2.encrypt = true;
        };

      networking.firewall.allowedTCPPorts = [ 443 ];
      services.nginx.enable = true;
      services.nginx.appendConfig = ''
        worker_processes 4;
        worker_rlimit_nofile 30000;
      '';
      services.nginx.eventsConfig = ''
            worker_connections 9000;
            use epoll;
            multi_accept on;
      '';
      services.nginx.httpConfig = ''
        log_format timed_combined '$remote_addr - $remote_user [$time_local]  "$request" $status $body_bytes_sent "$http_referer" "$http_user_agent" $request_time $upstream_response_time $pipe';
        server {
            listen               80;
            server_name   localhost;
            location /nginx_status {
                stub_status         on;
                access_log         off;
                allow        127.0.0.1;
                deny               all;
            }
        }
        server {
          server_name ${env.hostName};
          server_tokens off;

          listen [::]:443 default_server ssl ipv6only=off;

          ssl_certificate         /run/keys/server.crt;
          ssl_trusted_certificate /run/keys/server.crt;
          ssl_certificate_key     /run/keys/server.key;

          resolver 8.8.8.8;
          ssl_stapling on;
          ssl_stapling_verify on;
          ssl_session_cache shared:SSL:10m;
          ssl_session_timeout 5m;
          ssl_protocols TLSv1.2 TLSv1.1 TLSv1;
          ssl_prefer_server_ciphers on;

          ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES256-GCM-SHA384:DHE-RSA-AES128-GCM-SHA256:DHE-DSS-AES128-GCM-SHA256:kEDH+AESGCM:ECDHE-RSA-AES128-SHA256:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES256-SHA384:ECDHE-ECDSA-AES256-SHA384:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES256-SHA:DHE-RSA-AES128-SHA256:DHE-RSA-AES128-SHA:DHE-DSS-AES128-SHA256:DHE-RSA-AES256-SHA256:DHE-DSS-AES256-SHA:DHE-RSA-AES256-SHA:AES128-GCM-SHA256:AES256-GCM-SHA384:ECDHE-RSA-RC4-SHA:ECDHE-ECDSA-RC4-SHA:AES128:AES256:RC4-SHA:HIGH:!aNULL:!eNULL:!EXPORT:!DES:!3DES:!MD5:!PSK:!SHA1;


          access_log /var/spool/nginx/logs/access.log timed_combined buffer=16k;

          location / {
              proxy_pass         http://localhost:8082/;
              proxy_redirect     off;
              proxy_set_header   Host             $host;
              proxy_set_header   X-Real-IP        $remote_addr;
              proxy_set_header   X-Forwarded-For  $proxy_add_x_forwarded_for;
              proxy_set_header   X-Forwarded-Proto https;

              proxy_connect_timeout      180;
              proxy_send_timeout         600;
              proxy_read_timeout         600;

              client_max_body_size 0;

              break;
          }

        }

      '';

      nixpkgs.config.packageOverrides = pkgs: {
        nginx = pkgs.lib.overrideDerivation pkgs.nginx (a: { configureFlags = a.configureFlags ++ ["--with-http_stub_status_module"]; } );
      };

      systemd.services = {
        nginx.serviceConfig.LimitNOFILE = 32768;
      };

      services.dd-agent.jmxConfig = ''
          instances:
            - host: 127.0.0.1
              name: jmx_instance
              port: 7199

          init_config:
            conf:
              - include:
                  domain: java.lang
                  type: Threading
              - include:
                  domain: java.lang
                  type: GarbageCollector
      '';

      environment.etc =
        let
          nginx-config =
            pkgs.writeText "nginx.yaml" ''
              init_config:
              instances:
                -   nginx_status_url: http://127.0.0.1/nginx_status/
          '';
        in [
          { source = nginx-config;
            target = "dd-agent/conf.d/nginx.yaml";
          }
        ];

    };


  "database-${name}" =
    { config, pkgs, lib, resources, nodes, ... }:
    {
      imports = [
        ./database.nix
        <lbdevops/logicblox/production.nix>
      ];

      # Monocle setup
      telegraf.enableSteveDatabaseMetrics = true;
      telegraf.extraConfig = {
        procstat = {
            systemd_unit = "lb-server";
        };
        statsd = {
          service_address = ":8125";
          datadog_extensions = true;
        };
      };

      # pass s3Name
      system.build.s3Name = s3Name;

      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroupIds = [ "admin" resources.ec2SecurityGroups.database-sg.name ];
      deployment.ec2.subnetId = subnetId ;
      deployment.ec2.region = region;
      deployment.ec2.instanceType = if (vpcId != "") then "c4.8xlarge" else "c3.8xlarge";
      deployment.ec2.associatePublicIpAddress = true;
      deployment.ec2.instanceProfile = resources.iamRoles.database-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 100;
      deployment.ec2.ebsOptimized = false;

      systemd.services.export-billing = {
        description = "Export billing data";
        script = ''
          source /etc/profile
          lb web-client export -n -o s3://${s3Name}/reports/billing_data.csv http://localhost:8080/tdx/billing_data
        '';
        startAt = "*:15";
      };

      systemd.services.add-latest-lb-version = {
        enable = latestLb;
        description = "Add support for the latest LogicBlox versions in the LB Jobs cluster.";
        script = ''
          source /etc/profile
          /run/current-system/sw/bin/update-lb-versions
        '';
        startAt = "04:00";
      };

      fileSystems."/data" =
        { autoFormat = true;
          fsType = "xfs";
          device = getDeviceName config.deployment.ec2.instanceType false; #"/dev/xvdf";
          options = [ "noatime" "_netdev" ];
          ec2.size = 1000;
          ec2.volumeType = "gp2";
        };
    };

  "steve-${name}" =
    { config, pkgs, resources, lib, ... }:
    let
      block-ip = pkgs.writeScriptBin "block-ip" ''
        #! /usr/bin/env bash
        exec iptables -I INPUT 1 -s $1 -j DROP
      '';
    in
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGrouIds = [ "admin" resources.ec2SecurityGroups.frontend-sg.name ];
      deployment.ec2.subnetId = subnetId ;
      deployment.ec2.region = region;
      deployment.ec2.instanceType = if (vpcId != "") then "c4.xlarge" else "c3.xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.frontend-role.name;
      deployment.ec2.elasticIPv4 = env.elasticIPv4 or "";
      deployment.keys."server.key".text = builtins.readFile <global_creds/logicblox/server.key>;
      deployment.keys."server.crt".text = builtins.readFile <global_creds/logicblox/server.crt>;
      deployment.keys.google.keyFile = <global_creds/lb-jobs-gcp.json>;

      deployment.ec2.ebsInitialRootDiskSize = 100;

      imports = [ <lbdevops/logicblox/production.nix>
                  ./frontend.nix
                ];

      # Monocle setup
      telegraf.nginxInputUrl = "http://127.0.0.1/nginx_status";
      telegraf.extraConfig = {
        statsd = {
          service_address = ":8125";
          datadog_extensions = true;
        };
      };

      system.build.frontendConfig = frontendConfig;

      boot.kernel.sysctl = {
        "net.ipv4.ip_local_port_range" = "1024 65000";
        "net.ipv4.tcp_tw_reuse" = "1";
        "net.ipv4.tcp_fin_timeout" = "15";
        "net.core.netdev_max_backlog" = "4096";
        "net.core.rmem_max" = "16777216";
        "net.core.somaxconn" = "4096";
        "net.core.wmem_max" = "16777216";
        "net.ipv4.tcp_max_syn_backlog" = "20480";
        "net.ipv4.tcp_max_tw_buckets" = "400000";
        "net.ipv4.tcp_no_metrics_save" = "1";
        "net.ipv4.tcp_rmem" = "4096 87380 16777216";
        "net.ipv4.tcp_syn_retries" = "2";
        "net.ipv4.tcp_synack_retries" = "2";
        "net.ipv4.tcp_wmem" = "4096 65536 16777216";
        "vm.min_free_kbytes" = "65536";
      };

      networking.firewall.allowedTCPPorts = [ 443 ];

      environment.systemPackages = [ builds.frontend builds.client.build pkgs.jdk pkgs.awscli pkgs.nodejs block-ip];

      security.pam.loginLimits =
        [ { domain = "*"; item = "nofile"; type = "-"; value = "32768"; }
        ];

      services.nginx.enable = true;
      services.nginx.appendConfig = ''
        worker_processes 4;
        worker_rlimit_nofile 30000;
      '';
      services.nginx.eventsConfig = ''
            worker_connections 9000;
            use epoll;
            multi_accept on;
      '';
      services.nginx.httpConfig = ''
        log_format timed_combined '$remote_addr - $remote_user [$time_local]  "$request" $status $body_bytes_sent "$http_referer" "$http_user_agent" $request_time $upstream_response_time $pipe';
        log_format capture_requests '$request|$request_body';
        server {
            listen               80;
            server_name   localhost;
            location /nginx_status {
                stub_status         on;
                access_log         off;
                allow        127.0.0.1;
                deny               all;
            }
        }
        server {
          server_name ${env.hostName};
          server_tokens off;

          listen [::]:443 default_server ssl ipv6only=off;

          ssl_certificate         /run/keys/server.crt;
          ssl_trusted_certificate /run/keys/server.crt;
          ssl_certificate_key     /run/keys/server.key;

          resolver 8.8.8.8;
          ssl_stapling on;
          ssl_stapling_verify on;
          ssl_session_cache shared:SSL:10m;
          ssl_session_timeout 5m;
          ssl_protocols TLSv1.2 TLSv1.1 TLSv1;
          ssl_prefer_server_ciphers on;

          ssl_ciphers ECDHE-RSA-AES128-GCM-SHA256:ECDHE-ECDSA-AES128-GCM-SHA256:ECDHE-RSA-AES256-GCM-SHA384:ECDHE-ECDSA-AES256-GCM-SHA384:DHE-RSA-AES128-GCM-SHA256:DHE-DSS-AES128-GCM-SHA256:kEDH+AESGCM:ECDHE-RSA-AES128-SHA256:ECDHE-ECDSA-AES128-SHA256:ECDHE-RSA-AES128-SHA:ECDHE-ECDSA-AES128-SHA:ECDHE-RSA-AES256-SHA384:ECDHE-ECDSA-AES256-SHA384:ECDHE-RSA-AES256-SHA:ECDHE-ECDSA-AES256-SHA:DHE-RSA-AES128-SHA256:DHE-RSA-AES128-SHA:DHE-DSS-AES128-SHA256:DHE-RSA-AES256-SHA256:DHE-DSS-AES256-SHA:DHE-RSA-AES256-SHA:AES128-GCM-SHA256:AES256-GCM-SHA384:ECDHE-RSA-RC4-SHA:ECDHE-ECDSA-RC4-SHA:AES128:AES256:RC4-SHA:HIGH:!aNULL:!eNULL:!EXPORT:!DES:!3DES:!MD5:!PSK;


          access_log /var/spool/nginx/logs/access.log timed_combined buffer=16k;
          error_log /var/spool/nginx/logs/error.log error;

          error_page 503 /maintenance.json;

          location = / {
              try_files $uri /index.html;
              break;
          }
          location = /index.html {
              alias ${../www/index.html};
              break;
          }
          location = /maintenance.json {
              alias ${./maintenance.json};
          }
          location = /lb-steve-client.tgz {
              alias ${builds.client.binary_tarball}/lb-steve-client.tgz;
              break;
          }
          location / {
              if (-f /var/log/lb-steve-worker/maintenance) {
                  return 503;
              }

              proxy_pass         http://localhost:8081/;
              proxy_redirect     off;
              proxy_set_header   Host             $host;
              proxy_set_header   X-Real-IP        $remote_addr;
              proxy_set_header   X-Forwarded-For  $proxy_add_x_forwarded_for;
              proxy_set_header   X-Forwarded-Proto https;

              proxy_connect_timeout      180;
              proxy_send_timeout         600;
              proxy_read_timeout         600;

              client_max_body_size 0;

              break;
          }

        }

      '';

      nixpkgs.config.packageOverrides = pkgs: {
        nginx = pkgs.lib.overrideDerivation pkgs.nginx (a: { configureFlags = a.configureFlags ++ ["--with-http_stub_status_module"]; } );
      };

      systemd.services = {
        clean-status-messages =
          { description = "Clean status messages";
            path = [ pkgs.findutils ];
            script = ''
              find /var/log/lb-steve-worker/status -type f -mtime +5 -delete
            '';
            startAt = "03:00";
          };

        nginx.serviceConfig.LimitNOFILE = 32768;

      };

      services.dd-agent.jmxConfig = ''
          instances:
            - host: 127.0.0.1
              name: jmx_instance
              port: 7199

          init_config:
            conf:
              - include:
                  domain: java.lang
                  type: Threading
              - include:
                  domain: java.lang
                  type: GarbageCollector
          '';

      services.dd-agent.nginxConfig = ''
        init_config:
        instances:
          -   nginx_status_url: http://127.0.0.1/nginx_status/
      '';
    };

  "google-nat-${name}" =
    { config, pkgs, resources, lib, ... }:
    let
    in
    {
      deployment.targetEnv = "gce";
      deployment.gce = {
        instanceType = "n1-standard-2";
        project = gcpProject;
        accessKey = builtins.readFile accessKey;
        ipAddress = google-nat-ip;
        inherit serviceAccount;
        canIpForward = true;
        region =  "us-central1-a";
      };

      # NAT setup
      networking.firewall = {
        enable =  true;
        extraCommands = ''
          iptables -t nat -A POSTROUTING -o eth0 -j MASQUERADE
        '';
      };
      boot.kernel.sysctl."net.ipv4.ip_forward" = 1;
  };

  resources.gceRoutes."route-key-server-${name}" =
    { resources, ... }:
    {
      project = gcpProject;
      accessKey = builtins.readFile accessKey;
      inherit serviceAccount;
      destination =  resources.machines."key-server-${name}";
      name = "route-key-server-${name}";
      nextHop = resources.machines."google-nat-${name}";
      tags =  [ "worker" ];
    };

  resources.gceRoutes."route-gurobi-${name}" = {resources, ...}: {
    destination = "54.83.193.103/32" ;
    name = "route-gurobi-${name}";
    project = gcpProject;
    inherit serviceAccount;
    accessKey = builtins.readFile accessKey;
    nextHop = resources.machines."google-nat-${name}";
    tags =  [ "worker" ];
  };

  defaults =
    { config, lib, ... }:
    { imports = [ <lbdevops/nixos/local-modules/freeipa.nix>
                  <lbdevops/nixos/base/user-env.nix>
                  <lbdevops/logicblox/config/logging/rsyslogd.nix>
                  <lbdevops/nixos/local-modules/cloudwatch.nix>
                  <lbdevops/nixos/monitoring/telegraf/telegraf.nix>
                  <lbdevops/nixos/monitoring/clamav/clamav.nix>
                ];

      logging.sumologic.sumoToken = sumoToken;
      logging.sumologic.collectorHost = "syslog.collection.us1.sumologic.com";
      services.datadog-agent.enable = mkForce false;

      # Freeipa setup
      freeipa.enable = true;
      freeipa.allowedGroups = allowedGroups;
      freeipa.caCertificate = <global_creds/freeipa-creds/ca.crt>;
      freeipa.tlsCertificatePem = <global_creds/freeipa-creds/ldap_tls.pem>;
      freeipa.tlsCertificateKey = <global_creds/freeipa-creds/ldap_tls.key>;

      # Monocle setup
      telegraf.enable = true ;
      telegraf.password = builtins.readFile (<global_creds/monocle/kafkaProdPassword>);
      telegraf.enableWorkflowMonitors = false;
      telegraf.enableJolokiaAgent = false;

      nixops.enableDeprecatedAutoLuks = true;
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "worker-${name}-${workerName t}-${toString n}" (worker t (env.workers."${t}".instanceType or t))) (range 1 env.workers."${t}".number)) instanceTypes ) ) )
