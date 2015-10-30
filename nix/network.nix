{ 
  region ? "us-east-1"
, account ? "lb-jobs"
, accountId ? "826045886586"
, name
, logToken ? ""
}:
let
  environments = import ./environments.nix;
  env = environments."${name}";

  instanceTypes = builtins.attrNames env.workers;

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
        listen l1 0.0.0.0:443
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
    { config, pkgs, resources, nodes, ... }:
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

      # Tags are needed, so provisioner sees running worker instances for this
      # deployments, and does not start new instances if not necessary.
      deployment.ec2.tags.S3Bucket = s3Name;
      deployment.ec2.tags.IncomingQueue = sqsURL queue;
      deployment.ec2.tags.OutgoingQueue = sqsStatusURL;

      ec2.metadata = true;
    };

  builds = import ../. { platform_release = builder-config.getPlatform (import ../lb-version.nix); };
  s3Name = "steve-jobs-${name}";
  frontendConfig = pkgs.writeText "lb-steve-frontend.config" 
    ''
      [global]
      jvm_dump_dir = /tmp
      logdir_access = /var/log/lb-steve-worker
      logdir = /var/log/lb-steve-worker
      authentication_cache = $(LB_DEPLOYMENT_HOME)/authentication_cache
      tmpdir = /tmp

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
                "s3:List*"
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
      ips = [
        "38.104.0.30" # atlanta office / vpn
        "54.86.90.139" # ec2 vpn
        "107.20.158.107"
        "54.198.12.247"
        "83.87.147.147"
        "41.228.10.75"
        "41.228.10.115"
        "41.224.243.6"
        "196.203.15.130"
        "54.174.171.234"
        "54.77.137.60" # kiabi-dev
        "54.154.69.157" # kiabi-dev
        "52.17.16.150" # kiabi-dev
        "52.16.133.24" # kiabi-dev
        "54.76.162.121" # kiabi-dev
        "54.77.94.210" # kiabi-dev
        "52.0.109.86" # pdf-wfm-dev
        "52.5.128.41" # pdf-wfm-dev
        "54.208.53.160"
      ];
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
    { config, resources, nodes, ...}:
    let
      script = t: pkgs.writeScriptBin "run-provisioner-${workerName t}"
        ''
          #! /bin/sh
          source /etc/profile
          exec lb-steve-provisioner $@ --key-service https://${nodes."key-server-${name}".config.networking.privateIPv4}/keys --queue ${workerName t} --bucket ${s3Name} --incoming ${sqsURL t} --outgoing ${sqsStatusURL} --role ${resources.iamRoles.worker-role.name} --instance-type ${env.workers."${t}".instanceType or t} --spot-price ${env.workers."${t}".price} --percentage-spot ${env.workers."${t}".percentageSpot} --percentage-queue ${env.workers."${t}".percentageQueue or "0.6"} --max ${env.workers."${t}".max or "300"} --min ${env.workers."${t}".min or "0"}
        '';
      provisionScripts = map script instanceTypes;
      run-provisioner = t: "${script t}/bin/run-provisioner-${workerName t}";
      provisioner-service = t: {
        description = "Steve Provisioner";
        path = [ pkgs.jdk ];
        serviceConfig = {
          ExecStart = "${run-provisioner t}";
        };
        startAt = "*:0/5";
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
      ec2.metadata = true;

      imports = [
        <lbdevops/logicblox/production.nix>
        ./datadog/provisioner.nix
      ] ;

      environment.systemPackages = [ builds.worker pkgs.linuxPackages.sysdig ] ++ provisionScripts;
      systemd.services = listToAttrs (map (t: nameValuePair "run-provisioner-${workerName t}" (provisioner-service t) ) instanceTypes);
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
      ec2.metadata = true;
      deployment.keys."server.key".text = builtins.readFile <global_creds/logicblox/server.key>;
      deployment.keys."server.crt".text = builtins.readFile <global_creds/logicblox/server.crt>;

      imports = [
        <lbdevops/logicblox/production.nix>
      ] ;

      fileSystems."/keys" =
        { autoFormat = true;
          fsType = "xfs";
          device = "/dev/xvdf";
          options = "noatime";
          ec2.size = 20;
          ec2.encrypt = true;
        };

      networking.firewall.allowedTCPPorts = [ 443 ];
      services.nginx.enable = true;
      services.nginx.config = ''
        worker_processes 4;
        events {
            worker_connections 9000;
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


          access_log /var/spool/nginx/logs/access.log timed_combined;

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

      environment.etc =
        let
          jmx-config =
            pkgs.writeText "jmx.yaml" ''
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
          nginx-config =
            pkgs.writeText "nginx.yaml" ''
              init_config:
              instances:
                -   nginx_status_url: http://127.0.0.1/nginx_status/
          '';
        in [
          { source = jmx-config;
            target = "dd-agent/conf.d/jmx.yaml";
          }
          { source = nginx-config;
            target = "dd-agent/conf.d/nginx.yaml";
          }
        ];

    };


  "database-${name}" =
    { config, pkgs, resources, nodes, ... }:
    let
      platform = builder-config.getPlatform (import ../lb-version.nix);
    in
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "r3.2xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.database-role.name;
      deployment.ec2.ebsInitialRootDiskSize = 100;
      deployment.ec2.ebsOptimized = true;
      ec2.metadata = true;

      imports = [
        <lbdevops/logicblox/production.nix>
        <lbdevops/nixos/logicblox/lb40-module.nix>
        <lbdevops/nixos/logicblox/installer.nix>
        <lbdevops/nixos/logicblox/datadog/all.nix>
        ./datadog/database.nix
      ] ;

      services.logicblox.enable = true;
      services.logicblox.logicblox = platform.logicblox;
      services.logicblox.lbWeb = platform.bloxweb;
      services.logicblox.lbWorkflow = platform.lb-workflow;
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
      networking.firewall.allowedTCPPorts = [ 8080 55183 ];

      deployment.ec2.blockDeviceMapping."/dev/xvdg".size = 100;
      deployment.ec2.blockDeviceMapping."/dev/xvdh".size = 100;
      deployment.ec2.blockDeviceMapping."/dev/xvdg".deleteOnTermination = true;
      deployment.ec2.blockDeviceMapping."/dev/xvdh".deleteOnTermination = true;
      deployment.ec2.blockDeviceMapping."/dev/xvdg".volumeType = "gp2";
      deployment.ec2.blockDeviceMapping."/dev/xvdh".volumeType = "gp2";

      deployment.autoRaid0.raid.devices = [ "/dev/xvdg" "/dev/xvdh" ];

      fileSystems."/data" =
        { autoFormat = true;
          fsType = "xfs";
          device = "/dev/raid/raid";
          options = "noatime";
        };
    };

  "steve-${name}" =
    { config, pkgs, resources, ... }:
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
      ec2.metadata = true;

      imports = [ <lbdevops/logicblox/production.nix> ];

      networking.firewall.allowedTCPPorts = [ 443 ];

      environment.systemPackages = [ builds.frontend builds.client.build pkgs.jdk pkgs.awscli pkgs.nodejs];

      security.pam.loginLimits =
        [ { domain = "*"; item = "nofile"; type = "-"; value = "32768"; }
        ];

      services.nginx.enable = true;
      services.nginx.config = ''
        worker_processes 4;
        events {
            worker_connections 9000;
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


          access_log /var/spool/nginx/logs/access.log timed_combined;
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
        nginx.serviceConfig.LimitNOFILE = 32768;

        lb-steve-frontend = {
          description = "LB Steve Frontend";
          after = [ "network.target" ];
          wantedBy = [ "multi-user.target" ];
          path = [ pkgs.jdk pkgs.bash builds.frontend ];
          preStart = ''
            mkdir -p /var/log/lb-steve-worker
          '';
          environment.JAVA_ARGS = "-Xmx4800m -Xss2048k -Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=7199 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false";
          serviceConfig = {
            ExecStart = "${builds.frontend}/bin/lb-steve-frontend --config ${frontendConfig}";
            Restart = "always";
            RestartSec = "10";
          };
        };
      };

      environment.etc =
        let
          jmx-config =
            pkgs.writeText "jmx.yaml" ''
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
          nginx-config =
            pkgs.writeText "nginx.yaml" ''
              init_config:
              instances:
                -   nginx_status_url: http://127.0.0.1/nginx_status/
          '';
        in [
          { source = jmx-config;
            target = "dd-agent/conf.d/jmx.yaml";
          }
          { source = nginx-config;
            target = "dd-agent/conf.d/nginx.yaml";
          }
        ];

    };


  defaults =
    { imports = [ <lbdevops/logicblox/config/logging/logentries.nix> ];
      logging.logentries.logToken = logToken;
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "worker-${name}-${workerName t}-${toString n}" (worker t (env.workers."${t}".instanceType or t))) (range 1 env.workers."${t}".number)) instanceTypes ) ) )
