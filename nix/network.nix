{ 
  region ? "us-east-1"
, account ? "lb-jobs"
, accountId ? "826045886586"
, name
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

  pkgs = import <nixpkgs> { config.allowUnfree = true; };
  builder-config = import <config> {};
  inherit (pkgs.lib) getAttr;

  worker = type:
    { config, pkgs, resources, ... }:
    {
      imports = [ ./worker.nix ];

      lb-steve-worker.arguments = "--incoming ${resources.sqsQueues."${sqsName type}".name} --outgoing ${resources.sqsQueues."${sqsStatusName}".name} --bucket ${s3Name}";

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
      deployment.ec2.tags.IncomingQueue = sqsURL type;
      deployment.ec2.tags.OutgoingQueue = sqsStatusURL;

      ec2.metadata = true;
    };

  builds = import ../. {};
  s3Name = "steve-jobs-${name}";
  frontendConfig = pkgs.writeText "lb-steve-frontend.config" 
    ''
      [global]
      jvm_dump_dir = /tmp
      logdir_access = /var/log/lb-steve-worker
      logdir = /var/log/lb-steve-worker
      authentication_cache = $(LB_DEPLOYMENT_HOME)/authentication_cache
      tmpdir = /tmp
      jvm_args = -Xmx4800m -Xss2048k

      [handler:steve]
      database_prefix = http://database-${name}:8080/db/

      [state]
      implementation = dynamodb
      iam_role = default
      table = Job
      endpoint = dynamodb.${region}.amazonaws.com
      env_credentials = false

      ${pkgs.lib.concatMapStrings (t: ''
      [job-queue:${workerName t}]
      implementation = sqs
      iam_role = default
      env_credentials = false
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsName t}
      ${if (pkgs.lib.head instanceTypes == t) then "default = true" else ""}
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

  resources.ec2KeyPairs.kp = { inherit region ; accessKeyId = account; };
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
            },
            {
              "Action": [
                "dynamodb:*"
              ],
              "Effect": "Allow",
              "Resource": "*"
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
        "38.104.0.30"
        "107.20.158.107"
        "54.198.12.247"
        "83.87.147.147"
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
      ];
    in
      { config, resources, ... }:
      {
        inherit region;
        accessKeyId = account;
        description = "Security group for frontend";
        rules = map entry ips ++ map accountEntry accounts ++ [ { fromPort = 55183; toPort = 55183; sourceGroup.ownerId = accountId; sourceGroup.groupName = resources.ec2SecurityGroups.frontend-sg.name; } ];
      };

  "database-${name}" =
    { config, pkgs, resources, ... }:
    let
      platform = builder-config.getPlatform <platform_release>;
      script = t: pkgs.writeScriptBin "run-provisioner-${workerName t}"
        ''
          #! /bin/sh
          source /etc/profile
          exec lb-steve-provisioner --queue ${workerName t} --bucket ${s3Name} --incoming ${sqsURL t} --outgoing ${sqsStatusURL} --role ${resources.iamRoles.worker-role.name} --instance-type ${t} --spot-price ${env.workers."${t}".price} $@
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
      deployment.ec2.instanceType = "c3.xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.database-role.name;
      ec2.metadata = true;

      imports = [
        <lbdevops/logicblox/production.nix>
        <lbdevops/nixos/logicblox/lb40-module.nix>
        <lbdevops/nixos/logicblox/installer.nix>
      ] ;

      services.logicblox.enable = true;
      services.logicblox.logicblox = platform.logicblox;
      services.logicblox.lbWeb = platform.bloxweb;

      logicblox.application.installer = builds.database.build;
      networking.firewall.allowedTCPPorts = [ 8080 55183 ];

      environment.systemPackages = [ builds.worker ] ++ provisionScripts;
      systemd.services = listToAttrs (map (t: nameValuePair "run-provisioner-${workerName t}" (provisioner-service t) ) instanceTypes);
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
      ec2.metadata = true;

      imports = [ <lbdevops/logicblox/production.nix> ];

      networking.firewall.allowedTCPPorts = [ 443 ];

      environment.systemPackages = [ builds.frontend builds.client.build pkgs.jdk pkgs.awscli pkgs.nodejs];

      services.nginx.enable = true;
      services.nginx.httpConfig = ''
        server {
          server_name ${env.hostName};
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


          location = / {
              try_files $uri /index.html;
              break;
          }
          location = /index.html {
              alias ${../www/index.html};
              break;
          }
          location = /status.html {
              alias /tmp/status.html;
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

              proxy_connect_timeout      90;
              proxy_send_timeout         600;
              proxy_read_timeout         600;

              client_max_body_size 0;

              break;
          }

        }

      '';

      systemd.services = {
        lb-steve-frontend = {
          description = "LB Steve Frontend";
          after = [ "network.target" ];
          wantedBy = [ "multi-user.target" ];
          path = [ pkgs.jdk pkgs.bash builds.frontend ];
          preStart = ''
            mkdir -p /var/log/lb-steve-worker
          '';
          serviceConfig = {
            ExecStart = "${builds.frontend}/bin/lb-steve-frontend --config ${frontendConfig}";
            Restart = "always";
            RestartSec = "10";
          };
        };
      };
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "worker-${name}-${workerName t}-${toString n}" (worker t)) (range 1 env.workers."${t}".number)) instanceTypes ) ) )
