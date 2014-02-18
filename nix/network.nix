{ workers ? 4
, region ? "us-east-1"
, account ? "logicblox-dev"
}:
let
  pkgs = import <nixpkgs> {};
  worker = 
    { config, pkgs, ... }:
    {
    };

  ec2 =
    { resources, ... }:
    { deployment.ec2.accessKeyId = account;
      deployment.ec2.keyPair = resources.ec2KeyPairs.kp;
      deployment.ec2.securityGroups = [ "admin" "ssh-world" ];
      deployment.ec2.region = region;
      deployment.ec2.instanceType = "m1.medium";
      deployment.ec2.instanceProfile = resources.iamRoles.s3access;
      ec2.metadata = true;
    };

in
with pkgs.lib;
{
  network.description = "Steve Jobs";

  network.default = [ ec2 ];

  resources.ec2KeyPairs.kp = { inherit region; };

    resources.iamRoles.s3access =
      {
        accessKeyId = deploy-config.aws-account;
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
                "Resource": ["arn:aws:s3:::steve-jobs/*", "arn:aws:s3:::steve-jobs"]
              }
            ]
          }
        '';
      };


  frontend =
    { config, pkgs, ... }:
    { services.rabbitmq.enable = true;
      services.rabbitmq.listenAddress = "";
    };

} // (listToAttrs (map (n: nameValuePair "worker${toString n}" worker) (range 1 workers)))
