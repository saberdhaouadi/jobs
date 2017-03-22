{ region ? "us-east-1"
, account ? "lb-jobs"
, accountId ? "826045886586"
, name
, logToken ? ""
, catchRequests ? false
}:
let
  environments = import ./environments.nix;
  env = environments."${name}";

  instanceTypes = builtins.attrNames env.workers;

  amis = import ./amis.nix;

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

  key-proxy = region:
    { config, pkgs, resources, nodes, ... }:
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs."kp-${region}".name;
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "c3.large";
      deployment.ec2.elasticIPv4 = resources.elasticIPs."key-ip-${region}";

      networking.firewall.allowedTCPPorts = [ 443 ];

      services.haproxy.enable = true;
      services.haproxy.config = ''
        listen l1
            bind 0.0.0.0:443
            mode tcp
            clitimeout 180000
            srvtimeout 180000
            contimeout 4000
            server srv1 ${nodes."key-server-${name}".config.networking.publicIPv4}:443

        global
            user haproxy
      '';
    };

  worker = queue: type:
    { config, pkgs, resources, nodes, lib, ... }:
    {
      imports = [ ./worker.nix ];

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
      database_prefix = http://database-${name}:${if catchRequests then "80" else "8080"}/db

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

in
with pkgs.lib;
{
  network.description = "Steve Jobs [${name}]";

  resources.elasticIPs.key-ip-us-west-1 = { region = "us-west-1" ; accessKeyId = account; };
  "key-proxy-${name}-us-west-1" = key-proxy "us-west-1";

  resources.elasticIPs.key-ip-us-west-2 = { region = "us-west-2" ; accessKeyId = account; };
  "key-proxy-${name}-us-west-2" = key-proxy "us-west-2";

  resources.ec2KeyPairs.kp = { inherit region ; accessKeyId = account; };
  resources.ec2KeyPairs.kp-us-west-1 = { region = "us-west-1"; accessKeyId = account; };
  resources.ec2KeyPairs.kp-us-west-2 = { region = "us-west-2"; accessKeyId = account; };

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
                "ec2:TerminateInstances"
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
                "arn:aws:s3:::${s3Name}/backups/*"
              ]
            }
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
                "iam:PassRole"
              ],
              "Effect": "Allow",
              "Resource": [ "*" ]
            }
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
            }
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
        } ;
      ips = builtins.fromJSON (builtins.readFile ./ips.json);
      accountEntry = account:
        {
          fromPort = 443;
          toPort = 443;
          sourceGroup.ownerId = account;
          sourceGroup.groupName = "admin";
        } ;
      accounts = [
       "297794765570"
       "414877248210"
       "162071310369"
       "216775848791"
       "716415058944"
       "006491606506" # PDX Science
      ];
    in
      { config, resources, ... }:
      {
        inherit region;
        accessKeyId = account;
        description = "Security group for frontend";
        rules = map entry ips ++ map accountEntry accounts ++ [ { fromPort = 55183; toPort = 55183; sourceGroup.ownerId = accountId; sourceGroup.groupName = resources.ec2SecurityGroups.frontend-sg.name; } ];
      };

  "provisioner-${name}" =
    { config, resources, nodes, lib, ...}:
    let
      script = t: r: pkgs.writeScriptBin "run-provisioner-${workerName t}${lib.optionalString (r != "us-east-1") "-${r}"}"
        ''
          #! /bin/sh
          source /etc/profile
          exec lb-steve-provisioner $@ \
                 --region ${r} \
                 --ami ${amis."${r}"} \
                 --key-service https://${if r == "us-east-1" then nodes."key-server-${name}".config.networking.privateIPv4 else nodes."key-proxy-${name}-${r}".config.networking.privateIPv4}/keys \
                 --queue ${workerName t} \
                 --bucket ${s3Name} \
                 --incoming ${sqsURL t} \
                 --outgoing ${sqsStatusURL} \
                 --role ${resources.iamRoles.worker-role.name} \
                 --instance-type ${env.workers."${t}".instanceType or t} \
                 --spot-price ${env.workers."${t}".price} \
                 --percentage-spot ${env.workers."${t}".percentageSpot} \
                 --percentage-queue ${env.workers."${t}".percentageQueue or "0.6"} \
                 --max ${env.workers."${t}".max or "300"} \
                 --min ${env.workers."${t}".min or "0"}
        '';
      provisionScripts = lib.concatMap (r: map (i: script i r) instanceTypes) (builtins.attrNames amis);
      run-provisioner = t: "${script t "us-east-1"}/bin/run-provisioner-${workerName t}";
      provisioner-service = t: {
        description = "Steve Provisioner";
        path = [ pkgs.jdk ];
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
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "r3.large";
      deployment.ec2.instanceProfile = resources.iamRoles.provisioner-role.name;

      imports = [
        <lbdevops/logicblox/production.nix>
        ./datadog/provisioner.nix
      ] ;

      environment.systemPackages = [ builds.worker pkgs.linuxPackages.sysdig ] ++ provisionScripts;
      systemd.services = listToAttrs (map (t: nameValuePair "run-provisioner-${workerName t}" (provisioner-service t) ) instanceTypes) // { inherit terminate-impaired; };
      boot.extraModulePackages = [ pkgs.linuxPackages.sysdig ];
      boot.kernelModules = [ "sysdig-probe" ] ;
    };

  "key-server-${name}" =
    { config, resources, ...}:
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "r3.large";
      deployment.keys."server.key".text = builtins.readFile <global_creds/logicblox/server.key>;
      deployment.keys."server.crt".text = builtins.readFile <global_creds/logicblox/server.crt>;

      imports = [
        <lbdevops/logicblox/production.nix>
      ] ;

      fileSystems."/keys" =
        { autoFormat = true;
          fsType = "xfs";
          device = "/dev/xvdf";
          options = [ "noatime" ];
          ec2.size = 20;
          ec2.encrypt = true;
        };

      networking.firewall.allowedTCPPorts = [ 443 ];
      services.nginx.enable = true;
      services.nginx.appendConfig = ''
        worker_processes 4;
        worker_rlimit_nofile 30000;
        events {
            worker_connections 9000;
            use epoll;
            multi_accept on;
        }
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

          listen [::]:443 default_server ssl spdy ipv6only=off;

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

        lb-steve-key-server = {
          description = "LB Steve Frontend";
          after = [ "network.target" ];
          wantedBy = [ "multi-user.target" ];
          path = [ pkgs.jdk pkgs.bash builds.frontend ];
          preStart = ''
            mkdir -p /var/log/lb-steve-key-server
          '';
          environment.JAVA_ARGS = "-Xmx4800m -Xss2048k -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false";
          serviceConfig = {
            ExecStart = "${builds.key-server}/bin/lb-steve-key-server";
            Restart = "always";
            RestartSec = "10";
          };
        };
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
    let
      logicblox = builder-config.getLB (import ../lb-version.nix);
    in
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "c3.8xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.database-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 100;
      deployment.ec2.ebsOptimized = false;

      imports = [
        <lbdevops/logicblox/production.nix>
        <lbdevops/nixos/logicblox/lb40-module.nix>
        <lbdevops/nixos/logicblox/installer.nix>
        <lbdevops/nixos/logicblox/datadog/all.nix>
        ./datadog/database.nix
      ] ;

      services.logicblox.enable = true;
      services.logicblox.logicblox = logicblox;
      services.logicblox.config.lb-server = ''
        [workspace]
        auto_backup_mode=none
      '';

      services.logicblox.config.lb-web-server = ''
        [statsd]
        prefix = lb.web
        hostname = 127.0.0.1
        port = 8125
      '';

      logicblox.application.installer = builds.database.build;
      services.nginx.enable = lib.mkOverride 0 false;

      systemd.services.mitmproxy =
        { description = "mitmproxy";
          enable = catchRequests;
          wantedBy = [ "multi-user.target" ];
          serviceConfig = {
            ExecStart = "${pkgs.pythonPackages.mitmproxy}/bin/mitmdump --port 80 -R http://localhost:8080 -w /tmp/requests.txt -q --cadir /tmp/mitmproxy";
          };
        };

      networking.firewall.allowedTCPPorts = [ 8080 55183 80 ];

      fileSystems."/data" =
        { autoFormat = true;
          fsType = "xfs";
          device = "/dev/xvdf";
          options = [ "noatime" ];
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
      deployment.ec2.securityGroups = [ "admin" resources.ec2SecurityGroups.frontend-sg.name ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "c3.xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.frontend-role.name;
      deployment.ec2.elasticIPv4 = env.elasticIPv4 or "";
      deployment.keys."server.key".text = builtins.readFile <global_creds/logicblox/server.key>;
      deployment.keys."server.crt".text = builtins.readFile <global_creds/logicblox/server.crt>;
      deployment.ec2.ebsInitialRootDiskSize = 100;

      imports = [ <lbdevops/logicblox/production.nix> ];

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
        events {
            worker_connections 9000;
            use epoll;
            multi_accept on;
        }
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

          listen [::]:443 default_server ssl spdy ipv6only=off;

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

          location = / {
              try_files $uri /index.html;
              break;
          }
          location = /index.html {
              alias ${../www/index.html};
              break;
          }
          location = /lb-steve-client.tgz {
              alias ${builds.client.binary_tarball}/lb-steve-client.tgz;
              break;
          }
          location / {
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

        lb-steve-frontend = {
          description = "LB Steve Frontend";
          after = [ "network.target" ];
          wantedBy = [ "multi-user.target" ];
          path = [ pkgs.jdk pkgs.bash builds.frontend ];
          preStart = ''
            mkdir -p /var/log/lb-steve-worker
          '';
          environment.JAVA_ARGS = "-server -Xmx4800m -Xss2048k -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -XX:+PreserveFramePointer";
          serviceConfig = {
            ExecStart = "${builds.frontend}/bin/lb-steve-frontend --config ${frontendConfig}";
            Restart = "always";
            RestartSec = "10";
          };
        };
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

  defaults =
    { lib, ... }:
    { imports = [ <lbdevops/logicblox/config/logging/logentries.nix> ];
      logging.logentries.logToken = lib.mkOverride 0 logToken;
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "worker-${name}-${workerName t}-${toString n}" (worker t (env.workers."${t}".instanceType or t))) (range 1 env.workers."${t}".number)) instanceTypes ) ) )
