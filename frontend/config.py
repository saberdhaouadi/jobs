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

depends_on(
  logicblox_dep,
  lb_web_dep,
  commons_cli_dep,
  protocols_dep,
  frontend_database_dep)

bin_program('lb-steve-frontend')
config_file('config/lb-steve-frontend.config')
config_file('config/steve_service_config.json')
config_file('$(lb_web)/config/lb-web-server.config')

if subprocess.check_output(["lb", "version"]).strip() == "4.4.4":
    netty = '$(lb_web)/lib/java/netty-all-4.1.8.Final.jar'
else:
    netty = '$(lb_web)/lib/java/netty-all-4.1.10.Final.jar'

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  '$(lb_web)/lib/java/annotations.jar',
  '$(lb_web)/lib/java/bloxweb-credentials.jar',
  '$(lb_web)/lib/java/bloxweb-email.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-collections-3.2.1.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-httpclient-3.1.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',
  '$(lb_web)/lib/java/esapi-2.0.1.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/java-statsd-client-2.0.0.jar',
  '$(lb_web)/lib/java/jcommander-1.29.jar',
  '$(lb_web)/lib/java/jetty-client-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-continuation-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-http-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-io-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-security-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-server-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-servlet-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-util-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-websocket-7.6.7.v20120910.jar',
  netty,
  '$(lb_web)/lib/java/joda-time-2.8.1.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-json.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-1.2.13.jar',
  '$(lb_web)/lib/java/not-yet-commons-ssl-0.3.9.jar',
  '$(lb_web)/lib/java/opensaml-2.6.4.jar',
  '$(lb_web)/lib/java/openws-1.5.4.jar',
  '$(lb_web)/lib/java/protobuf-2.6.1.jar',
  '$(lb_web)/lib/java/scala-library.jar',
  '$(lb_web)/lib/java/servlet-api-2.5.jar',
  '$(lb_web)/lib/java/velocity-1.7.jar',
  '$(lb_web)/lib/java/xmlsec-1.5.7.jar',
  '$(lb_web)/lib/java/xmltooling-1.4.4.jar',
  '$(lb_web)/lib/java/google-api-client-1.19.1.jar',
  '$(lb_web)/lib/java/google-http-client-1.19.0.jar',
  '$(lb_web)/lib/java/google-http-client-jackson2-1.19.0.jar',
  '$(lb_web)/lib/java/google-api-services-storage-v1-rev26-1.19.1.jar',
  '$(lb_web)/lib/java/google-oauth-client-1.19.0.jar',

  '$(lb_web)/lib/java/s3lib-0.2.jar',
  '$(lb_web)/lib/java/commons-io-2.4.jar',
  '$(lb_web)/lib/java/aws-java-sdk-1.11.102.jar',
  '$(lb_web)/lib/java/httpclient-4.5.2.jar',
  '$(lb_web)/lib/java/httpcore-4.4.4.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.6.0.jar',
  '$(lb_web)/lib/java/jackson-core-2.6.6.jar',
  '$(lb_web)/lib/java/jackson-databind-2.6.6.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.3.jar',

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

