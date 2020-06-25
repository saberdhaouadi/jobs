from lbconfig.api import *
from lbconfig import core

lbconfig_package(
  'lb-steve-worker',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-worker',
  default_targets=['jars', 'findbugs'])


commons_exec_dep = (
  "commons_exec", {'default_path': "/opt/logicblox/deps/commons-exec-1.2"}
)
commons_cli_dep = (
  "commons_cli", {'default_path': "/opt/logicblox/deps/commons-cli-1.2"}
)
protocols_dep = (
  "protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"}
)

aws_java_sdk_dep = ("aws_java_sdk", {'default_path': "/opt/logicblox/cloud-store"})

depends_on(
    logicblox_dep,
    lb_web_dep,
    commons_exec_dep,
    protocols_dep,
    aws_java_sdk_dep,
    commons_cli_dep)

bin_program('lb-steve-worker')
bin_program('lb-steve-provisioner')
config_file('$(lb_web)/config/lb-web-client.config')

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  '$(lb_web)/lib/java/joda-time-2.8.1.jar',
  '$(lb_web)/lib/java/jcommander-1.29.jar',
  '$(logicblox)/lib/java/commons-io-2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/google-http-client-1.32.0.jar',
  '$(lb_web)/lib/java/cloudstore-0.2.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-core-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-databind-2.10.4.jar',
  '$(lb_web)/lib/java/httpclient-4.5.2.jar',
  '$(lb_web)/lib/java/httpcore-4.4.4.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.3.jar',
  '$(lb_web)/lib/java/netty-all-4.1.46.Final.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',

  '$(aws_java_sdk)/lib/java/aws-java-sdk-1.11.102.jar',

  '$(commons_exec)/lib/java/commons-exec.jar',
  '$(commons_cli)/lib/java/commons-cli.jar',
  
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',  
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-1.2.13.jar',
  '$(lb_web)/lib/java/protobuf-2.6.1.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar'
]

jar(
   name = 'lb-steve-worker',
   srcdir = 'java',
   findbugs = True,
   classpath = classpath)
core.g_rules['findbugs'].input = set()

install_dir('nix','nix')

link_libs(classpath)

install_files(classpath, 'lib/java')
