from lbconfig.api import *
from lbconfig import core

deps = os.getenv('LB_UNIVERSE_DEPS', '/opt/logicblox/lb-universe-deps')

lbconfig_package(
  'lb-steve-frontend',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend',
  default_targets=['jars', 'findbugs'])

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
frontend_database_dep = ("frontend_database", {'default_path': "/opt/logicblox/lb-steve-frontend-database"})

commons_cli_dep = ( "commons_cli", {'default_path': deps})
aws_java_sdk_dep = ("aws_java_sdk", {'default_path': "/opt/logicblox/s3lib"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  commons_cli_dep,
  aws_java_sdk_dep,
  protocols_dep,
  frontend_database_dep)

bin_program('lb-steve-frontend')
config_file('config/lb-steve-frontend.config')
config_file('config/steve_service_config.json')
config_file('$(lb_web)/config/lb-web-server.config')
config_file('$(lb_web)/config/lb-web-client.config')

if subprocess.check_output(["lb", "version"]).strip() == "4.4.4":
    netty = '$(lb_web)/lib/java/netty-all-4.1.8.Final.jar'
else:
    netty = '$(lb_web)/lib/java/netty-all-4.1.22.Final.jar'

classpath = [
  '$(aws_java_sdk)/lib/java/aws-java-sdk-1.11.560.jar',      
  '$(protocols)/lib/java/lb-steve-protocols.jar',
  '$(lb_web)/lib/java/annotations.jar',
  '$(lb_web)/lib/java/bloxweb-credentials.jar',
  '$(lb_web)/lib/java/bloxweb-email.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/java-statsd-client-2.0.0.jar',
  '$(lb_web)/lib/java/jcommander-1.29.jar',
  netty,
  '$(lb_web)/lib/java/joda-time-2.8.1.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-json.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-1.2.13.jar',
  '$(lb_web)/lib/java/protobuf-2.6.1.jar',
  '$(lb_web)/lib/java/scala-library.jar',
  '$(lb_web)/lib/java/servlet-api-2.5.jar',
  '$(lb_web)/lib/java/velocity-1.7.jar',
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

  '$(lb_web)/lib/java/cloudstore-0.2.jar',
  '$(logicblox)/lib/java/commons-io-2.4.jar',
  '$(lb_web)/lib/java/httpclient-4.5.2.jar',
  '$(lb_web)/lib/java/httpcore-4.4.4.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.6.0.jar',
  '$(lb_web)/lib/java/jackson-core-2.6.6.jar',
  '$(lb_web)/lib/java/jackson-databind-2.6.6.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.3.jar',

  #opensamlv3 dependencies
  '$(lb_web)/lib/java/bcprov-jdk15on-1.54.jar',
  '$(lb_web)/lib/java/commons-collections-3.2.1.jar',
  '$(lb_web)/lib/java/cryptacular-1.1.1.jar',
  '$(lb_web)/lib/java/java-support-7.3.0.jar',
  '$(lb_web)/lib/java/metrics-core-3.1.2.jar',
  '$(lb_web)/lib/java/opensaml-core-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-messaging-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-profile-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-saml-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-saml-impl-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-security-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-security-impl-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-soap-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-soap-impl-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-storage-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-xmlsec-api-3.3.0.jar',
  '$(lb_web)/lib/java/opensaml-xmlsec-impl-3.3.0.jar',
  '$(lb_web)/lib/java/slf4j-api-1.7.12.jar',
  '$(lb_web)/lib/java/stax2-api-3.1.4.jar',
  '$(lb_web)/lib/java/stax-api-1.0-2.jar',
  '$(lb_web)/lib/java/woodstox-core-asl-4.4.1.jar',
  '$(lb_web)/lib/java/xmlsec-2.0.5.jar',
  '$(commons_cli)/lib/java/commons-cli.jar'
]

test_classpath = classpath + [
  '$(logicblox)/lib/java/lb-connectblox.jar',
  '$(logicblox)/lib/java/junit-4.8.2.jar'
]

jar(
  name = 'lb-steve-frontend',
  srcdir = 'java',
  classpath = classpath,
  findbugs = True,
  scala = True)
core.g_rules['findbugs'].input = set()

link_libs(test_classpath)

install_files(classpath, 'lib/java')

#
# Tests
#

# dummy library that contains the frontend-database
check_lb_library(
  name = 'lb_steve_frontend_test',
  srcdir = 'tests'
)

# empty workspace that contains the frontend-database to use in tests
check_lb_workspace(
  name='lb-steve-frontend-test',
  libraries=['lb_steve_frontend_test']
)

check_jar(
  name='lb-steve-frontend-suite',
  main='com.logicblox.steve.tests.Main',
  srcdirs=['tests/java'],
  deps=['lb-steve-frontend'],
  classpath=test_classpath,
  workspaces=['lb-steve-frontend-test'],
  resources={'tests/java/com/logicblox/steve/db/users.csv': 'com/logicblox/steve/db/users.csv'}
  #resources={'tests/users.csv':'com/logicblox/steve/db/users.csv'}
)
