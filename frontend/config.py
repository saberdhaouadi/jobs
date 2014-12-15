from lbconfig.api import *

lbconfig_package(
  'lb-steve-frontend',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend',
  default_targets=['jars'])

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
frontend_database_dep = ("frontend_database", {'default_path': "/opt/logicblox/lb-steve-frontend-database"})

s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib"})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.9.8"})
commons_cli_dep = ( "commons_cli", {'default_path': "/opt/logicblox/deps/commons-cli-1.2"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
  aws_dep,
  commons_cli_dep,
  protocols_dep,
  frontend_database_dep)

bin_program('lb-steve-frontend')
config_file('config/lb-steve-frontend.config')
config_file('config/steve_service_config.json')
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
  '$(lb_web)/lib/java/commons-logging-1.1.1.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/httpclient-4.2.3.jar',
  '$(lb_web)/lib/java/httpcore-4.2.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-core-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-databind-2.1.1.jar',
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
  '$(lb_web)/lib/java/joda-time-2.2.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-json.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/log4j-1.2.13.jar',
  '$(lb_web)/lib/java/protobuf-2.5.0.jar',
  '$(lb_web)/lib/java/protobuf-java-format-1.3.jar',
  '$(lb_web)/lib/java/scala-library.jar',
  '$(lb_web)/lib/java/servlet-api-2.5.jar',
  '$(lb_web)/lib/java/webservices-api.jar',
  '$(lb_web)/lib/java/webservices-rt.jar',

  '$(s3lib)/lib/java/s3lib-0.2.jar',
  '$(s3lib)/lib/java/commons-io-2.4.jar',

  '$(aws)/lib/java/aws-java-sdk-1.9.8.jar',

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
  scala = True)

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

