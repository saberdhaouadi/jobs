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
  sqsQueue = { inherit region ; accessKeyId = account; visibilityTimeout = 1800; };
  sqsResultsQueue = { inherit region ; accessKeyId = account; };
  sqsQueues = with pkgs.lib; listToAttrs (map (n: nameValuePair (sqsName n) sqsQueue) instanceTypes) ;
  sqsResultsQueues = with pkgs.lib; listToAttrs (map (n: nameValuePair (sqsResultsName n) sqsResultsQueue) instanceTypes) ;

  pkgs = import <nixpkgs> {};
  builder-config = import <config> {};
  inherit (pkgs.lib) getAttr;

  worker = type:
    { config, pkgs, resources, ... }:
    {
      imports = [ ./worker.nix <lbdevops/logicblox/service-config/datadog.nix> ];

      lb-steve-worker.arguments = "--incoming ${resources.sqsQueues."${sqsName type}".name} --outgoing ${resources.sqsQueues."${sqsResultsName type}".name}";

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
      [state]
      implementation = dynamodb
      table = Job
      env_credentials = true
      endpoint = dynamodb.${region}.amazonaws.com

      [job-queue]
      implementation = sqs
      env_credentials = true
      sqs_endpoint = sqs.${region}.amazonaws.com
      sqs_queue_url = https://sqs.${region}.amazonaws.com/${accountId}/${sqsName "m2.xlarge"}

      [status-queue]
      implementation = sqs
      env_credentials = true
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
  resources.s3Buckets."${s3Name}" = { inherit region ; accessKeyId = account; };

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
              "Resource": ["arn:aws:s3:::${s3Name}/*", "arn:aws:s3:::${s3Name}", "arn:aws:s3:::logicblox-downloads" , "arn:aws:s3:::logicblox-downloads/*"]
            },
            {
              "Action": [
                "sqs:ChangeMessageVisibility",
                "sqs:DeleteMessage",
                "sqs:ReceiveMessage",
                "sqs:SendMessage"
              ],
              "Effect": "Allow",
              "Resource": [
                ${pkgs.lib.concatStringsSep "," (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsName t}".name}",
                "arn:aws:sqs:${region}:${accountId}:${resources.sqsQueues."${sqsResultsName t}".name}"
                '') instanceTypes)
                }
              ]
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
                "sqs:SendMessage"
              ],
              "Effect": "Allow",
              "Resource": [
               ${pkgs.lib.concatStringsSep "," (map (t: ''
                "arn:aws:sqs:${region}:${accountId}:steve-jobs-${name}-${sqsName t}",
                "arn:aws:sqs:${region}:${accountId}:steve-jobs-${name}-${sqsResultsName t}"
                '') instanceTypes)
                }
              ]
            },
            { 
              "Action": [
                "s3:Get*",
                "s3:Put*",
                "s3:List*"
              ],
              "Effect": "Allow",
              "Resource": ["arn:aws:s3:::${s3Name}/*", "arn:aws:s3:::${s3Name}"]
            }
          ]
        }
      '';
    };

  frontend =
    { config, pkgs, resources, ... }:
    {
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" "ssh-world" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "m1.medium";
      deployment.ec2.instanceProfile = resources.iamRoles.frontend-role.name;
      ec2.metadata = true;
    };

} // (listToAttrs (concatLists ( map (t: map (n: nameValuePair "${workerName t}-worker${toString n}" (worker t)) (range 1 (getAttr t workers))) instanceTypes ) ) )
