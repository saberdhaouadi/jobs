from lbconfig.api import *
from lbconfig import core

lbconfig_package(
  'lb-key-server',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-key-server',
  default_targets=['jars'])
  #default_targets=['jars', 'findbugs'])

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
commons_cli_dep = ( "commons_cli", {'default_path': "/opt/logicblox/deps/commons-cli-1.2"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  protocols_dep,
  commons_cli_dep)

bin_program('lb-steve-key-server')

config_file('config/lb-steve-key-server.config')
config_file('config/key_service_config.json')
config_file('$(lb_web)/config/lb-web-server.config')

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  # all jars in lb-web, commenting out some that we don't need
  '$(lb_web)/lib/java/annotations.jar',
  '$(lb_web)/lib/java/bloxweb-credentials.jar',
  '$(lb_web)/lib/java/bloxweb-email.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.3.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/httpclient-4.5.2.jar',
  '$(lb_web)/lib/java/httpcore-4.4.4.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-core-2.10.4.jar',
  '$(lb_web)/lib/java/jackson-databind-2.10.4.jar',
  '$(lb_web)/lib/java/java-statsd-client-2.0.0.jar',
  '$(lb_web)/lib/java/jcommander-1.29.jar',
  '$(lb_web)/lib/java/joda-time-2.8.1.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-json.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-api-2.17.1.jar',
  '$(lb_web)/lib/java/log4j-core-2.17.1.jar',
  '$(lb_web)/lib/java/protobuf-2.6.1.jar',
  '$(lb_web)/lib/java/scala-library.jar',
  '$(lb_web)/lib/java/servlet-api-2.5.jar',
  '$(lb_web)/lib/java/google-api-services-storage-v1-rev20190910-1.30.3.jar',
  '$(lb_web)/lib/java/google-api-client-1.30.3.jar',
  '$(lb_web)/lib/java/google-http-client-1.32.0.jar',
  '$(lb_web)/lib/java/google-http-client-jackson2-1.32.0.jar',
  '$(lb_web)/lib/java/google-oauth-client-1.30.2.jar',
  '$(lb_web)/lib/java/grpc-context-1.22.1.jar',
  '$(lb_web)/lib/java/j2objc-annotations-1.3.jar',
  '$(lb_web)/lib/java/opencensus-api-0.24.0.jar',
  '$(lb_web)/lib/java/opencensus-contrib-http-util-0.24.0.jar',
  '$(lb_web)/lib/java/jsr305-3.0.2.jar',
  '$(lb_web)/lib/java/netty-all-4.1.46.Final.jar',

  '$(lb_web)/lib/java/cloudstore-0.2.jar',
  '$(logicblox)/lib/java/commons-io-2.4.jar',
  '$(lb_web)/lib/java/aws-java-sdk-1.11.102.jar',

  '$(logicblox)/lib/java/lb-connectblox.jar',

  '$(commons_cli)/lib/java/commons-cli.jar'
]

jar(
  name = 'lb-steve-key-server',
  srcdir = 'java',
  #findbugs = True,
  classpath = classpath)
#core.g_rules['findbugs'].input = set()

link_libs(classpath)

install_files(classpath, 'lib/java')

