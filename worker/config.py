from lbconfig.api import *

lbconfig_package(
  'lb-steve-worker',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-worker',
  default_targets=['jars'])


s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.7.1"})

commons_exec_dep = (
  "commons_exec", {'default_path': "/opt/logicblox/deps/commons-exec-1.2"}
)
commons_cli_dep = (
  "commons_cli", {'default_path': "/opt/logicblox/deps/commons-cli-1.2"}
)
protocols_dep = (
  "protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"}
)

depends_on(
    logicblox_dep,
    lb_web_dep,
    s3lib_dep,
    aws_dep,
    commons_exec_dep,
    protocols_dep,
    commons_cli_dep)

bin_program('lb-steve-worker')
bin_program('lb-steve-provisioner')

jar(
   name = 'lb-steve-worker',
   srcdir = 'java',
   classpath = [
     "$(protocols)/lib/java/lb-steve-protocols.jar",
      '$(aws)/lib/java/aws-java-sdk-1.7.1.jar',
     "$(s3lib)/lib/java/jcommander-1.29.jar",
     "$(s3lib)/lib/java/commons-io-2.4.jar",
     "$(s3lib)/lib/java/guava-15.0.jar",
     "$(s3lib)/lib/java/s3lib-0.2.jar",
     "$(commons_exec)/lib/java/commons-exec.jar",
     "$(commons_cli)/lib/java/commons-cli.jar",
     "$(logicblox)/lib/java/protobuf-2.5.0.jar",
     "$(lb_web)/lib/java/protobuf-java-format-1.3.jar",
     "$(lb_web)/lib/java/gson-2.2.4.jar",
     "$(aws)/lib/java/joda-time-2.2.jar",
     "$(lb_web)/lib/java/lb-web-client.jar",
     "$(lb_web)/lib/java/lb-web-server.jar",
   ])

install_dir('nix','nix')

rule(
  output='install',
  input = [],
  commands = [
    'cp -f $(s3lib)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/jackson*.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/aws-java-sdk-1.7.1.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/http*.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/joda-*.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/protobuf-2.5.0.jar $(prefix)/lib/java',
    'cp -f $(protocols)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(commons_exec)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(commons_cli)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/protobuf-java*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/lb-web-client.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/lb-web-server.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/gson-2.2.4.jar $(prefix)/lib/java',
  ]
)

