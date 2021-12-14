from lbconfig.api import *
from lbconfig import core

lbconfig_package(
  'lb-steve-client',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-client',
  default_targets=['jars'])
  #default_targets=['jars', 'findbugs'])

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
aws_java_sdk_dep = ("aws_java_sdk", {'default_path': "/opt/logicblox/cloud-store"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  aws_java_sdk_dep,
  protocols_dep)

bin_program('lb-steve')
config_file('config/lb-steve-client.config')
config_file('$(lb_web)/config/lb-web-client.config')

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  '$(lb_web)/lib/java/cloudstore-0.2.jar',
  '$(logicblox)/lib/java/commons-io-2.4.jar',
  '$(lb_web)/lib/java/httpclient-4.5.2.jar',
  '$(lb_web)/lib/java/httpcore-4.4.4.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-core-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-databind-2.10.4.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.3.jar',
  '$(lb_web)/lib/java/netty-all-4.1.46.Final.jar',

  '$(aws_java_sdk)/lib/java/aws-java-sdk-1.11.102.jar',

  '$(lb_web)/lib/java/annotations.jar',
  '$(lb_web)/lib/java/bloxweb-credentials.jar',
  '$(lb_web)/lib/java/bloxweb-email.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',
  '$(lb_web)/lib/java/google-http-client-1.32.0.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/java-statsd-client-2.0.0.jar',
  '$(lb_web)/lib/java/jcommander-1.29.jar',
  '$(lb_web)/lib/java/joda-time-2.8.1.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-json.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-1.2-api-2.15.0.jar',
  '$(lb_web)/lib/java/protobuf-2.6.1.jar'
]

jar(
   name = 'lb-steve-client',
   srcdir = 'java',
   #findbugs = True,
   classpath = classpath)
#core.g_rules['findbugs'].input = set()

link_libs(classpath)

install_files(classpath, 'lib/java')
