{ workers ? 1
, region ? "us-east-1"
, account ? "logicblox-dev"
}:
let
  pkgs = import <nixpkgs> {};
  builder-config = import <config> {};
  worker = 
    { config, pkgs, resources, ... }:
    {
      imports = [ ./worker.nix ];
      deployment.targetEnv = "ec2";
      deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp.name;
      deployment.ec2.securityGroups = [ "admin" "ssh-world" "lb-steve-worker" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "m2.xlarge";
      deployment.ec2.instanceProfile = resources.iamRoles.worker-role.name;
      ec2.metadata = true;
    };

  builds = import ../. {};

in
with pkgs.lib;
{
  network.description = "Steve Jobs";

  resources.ec2KeyPairs.kp = { inherit region ; accessKeyId = account; };

  resources.iamRoles.worker-role =
    {
      accessKeyId = account;
      policy = ''
        {
          "Statement": [
            {
              "Action": [
                "s3:Get*",
                "s3:Put*",
                "s3:List*"
              ],
              "Effect": "Allow",
              "Resource": ["arn:aws:s3:::steve-jobs/*", "arn:aws:s3:::steve-jobs", "arn:aws:s3:::logicblox-downloads" , "arn:aws:s3:::logicblox-downloads/*"]
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
                "arn:aws:sqs:us-east-1:297794765570:steve-jobs",
                "arn:aws:sqs:us-east-1:297794765570:steve-jobs-results"
              ]
            }
          ]
        }
      '';
    };

  resources.iamRoles.frontend-role =
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
                "arn:aws:sqs:us-east-1:297794765570:steve-jobs",
                "arn:aws:sqs:us-east-1:297794765570:steve-jobs-results"
              ]
            }
          ]
        }
      '';
    };

/*
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
      networking.enableIPv6 = false;
    };
*/

} // (listToAttrs (map (n: nameValuePair "worker${toString n}" worker) (range 1 workers)))
