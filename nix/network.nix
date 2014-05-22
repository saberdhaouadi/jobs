{ workers ? { "m2.xlarge" = 1; "m2.4xlarge" = 0; }
, instanceTypes ? [ "m2.xlarge" "m2.4xlarge" ]
, region ? "us-east-1"
, account ? "logicblox-dev"
, accountId ? "297794765570"
, name
}:
let
  workerName = type : pkgs.lib.replaceChars ["."] ["-"] type;
  sqsName = type : "steve-jobs-${name}-${pkgs.lib.replaceChars ["."] ["-"] type}";
  sqsResultsName = type: "${sqsName type}-results";
  sqsQueue = type: { inherit region ; accessKeyId = account; visibilityTimeout = 1800; name = sqsName type;};
  sqsResultsQueue = type: { inherit region ; accessKeyId = account; name = sqsResultsName type; };
  sqsURL = type: "https://sqs.${region}.amazonaws.com/${accountId}/${sqsName type}";
  sqsResultsURL = type: "https://sqs.${region}.amazonaws.com/${accountId}/${sqsResultsName type}";
  sqsQueues = with pkgs.lib; listToAttrs (map (n: nameValuePair (sqsName n) (sqsQueue n)) instanceTypes) ;
  sqsResultsQueues = with pkgs.lib; listToAttrs (map (n: nameValuePair (sqsResultsName n) (sqsResultsQueue n)) instanceTypes) ;

  pkgs = import <nixpkgs> { config.allowUnfree = true; };
  builder-config = import <config> {};
  inherit (pkgs.lib) getAttr;
  jdk7_jce = pkgs.oraclejdk7.override (a: { installjce = true; }) ;

  worker = type:
    { config, pkgs, resources, ... }:
    {
      imports = [ ./worker.nix <lbdevops/logicblox/service-config/datadog.nix> ];

      lb-steve-worker.arguments = "--incoming ${resources.sqsQueues."${sqsName type}".name} --outgoing ${resources.sqsQueues."${sqsResultsName type}".name} --bucket ${s3Name}";

      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" "ssh-world" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = type;
      deployment.ec2.instanceProfile = resources.iamRoles.worker-role.name;
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

      [state]
      implementation = dynamodb
      iam_role = default
      table = Job
      endpoint = dynamodb.${region}.amazonaws.com

      [job-queue]
      implementation = sqs
      iam_role = default
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsName "m2.xlarge"}

      [status-queue]
      implementation = sqs
      iam_role = default
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsResultsName "m2.xlarge"}

      [job-implementations]
      prefix = s3://${s3Name}/jobs-impl
    '';

in
with pkgs.lib;
{
  network.description = "Steve Jobs [${name}]";

  resources.ec2KeyPairs.kp = { inherit region ; accessKeyId = account; };
  resources.sqsQueues = sqsQueues // sqsResultsQueues;
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
                "arn:aws:s3:::steve-jobs/*",
                "arn:aws:s3:::steve-jobs",
                "arn:aws:s3:::${s3Name}/*",
                "arn:aws:s3:::${s3Name}",
                "arn:aws:s3:::logicblox-downloads",
                "arn:aws:s3:::logicblox-downloads/*"
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
                ${pkgs.lib.concatStringsSep "," (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsResultsName t}".name}"
                '') instanceTypes)
                }
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
               ${pkgs.lib.concatStringsSep "," (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsResultsName t}".name}"
                '') instanceTypes)
                }
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
              "Resource": ["arn:aws:s3:::${s3Name}/*", "arn:aws:s3:::${s3Name}"]
            },
            {
              "Action": [
                "dynamodb:*"
              ],
              "Effect": "Allow",
              "Resource": "*"
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

  frontend =
    { config, pkgs, resources, ... }:
    let
      run-provisioner =
        pkgs.writeScriptBin "run-provisioner"
          ''
            #! /bin/sh
            source /etc/profile
            exec lb-steve-provisioner --bucket ${s3Name} --incoming ${sqsURL "m2.xlarge"} --outgoing ${sqsResultsURL "m2.xlarge"} --max 200 --role ${resources.iamRoles.worker-role.name} $@
          '';
    in
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" "ssh-world" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "m1.medium";
      deployment.ec2.instanceProfile = resources.iamRoles.frontend-role.name;
      ec2.metadata = true;

      networking.firewall.allowedTCPPorts = [8080];

      environment.systemPackages = [ builds.frontend builds.client builds.worker run-provisioner jdk7_jce pkgs.awscli ];
      systemd.services.lb-steve-frontend = {
        description = "LB Steve Frontend";
        after = [ "network.target" ];
        wantedBy = [ "multi-user.target" ];
        path = [ jdk7_jce pkgs.bash builds.frontend ];
        preStart = ''
          mkdir -p /var/log/lb-steve-worker
        '';
        serviceConfig = {
          ExecStart = "${builds.frontend}/bin/lb-steve-frontend --config ${frontendConfig}";
          Restart = "always";
          RestartSec = "10";
        };
      };

      systemd.services.run-provisioner = {
        description = "Steve Provisioner";
        path = [ jdk7_jce ];
        serviceConfig = {
          ExecStart = "${run-provisioner}/bin/run-provisioner";
        };
      };

      systemd.timers.run-provisioner =
        { wantedBy = [ "timers.target" ];
          timerConfig.OnCalendar = "*:0/5";
        };
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "${workerName t}-worker${toString n}" (worker t)) (range 1 (getAttr t workers))) instanceTypes ) ) )
