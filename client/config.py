from lbconfig.api import *

lbconfig_package(
  'lb-steve-client',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-client',
  default_targets=['jars'])

s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})
protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.9.8"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
  aws_dep,
  protocols_dep)

bin_program('lb-steve')
config_file('config/lb-steve-client.config')

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  '$(s3lib)/lib/java/s3lib-0.2.jar',
  '$(s3lib)/lib/java/commons-io-2.4.jar',
  '$(aws)/lib/java/aws-java-sdk-1.9.8.jar',

  # all jars in lb-web, commenting out some that we don't need
  '$(lb_web)/lib/java/annotations.jar',
  #'$(lb_web)/lib/java/aws-java-sdk-1.9.8.jar',
  '$(lb_web)/lib/java/bloxweb-credentials.jar',
  '$(lb_web)/lib/java/bloxweb-email.jar',
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-configuration-1.8.jar',
  '$(lb_web)/lib/java/commons-lang-2.6.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.1.jar',
  #'$(lb_web)/lib/java/datalog-generator.jar',
  #'$(lb_web)/lib/java/esapiport.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',
  '$(lb_web)/lib/java/guava-15.0.jar',
  '$(lb_web)/lib/java/httpclient-4.2.3.jar',
  '$(lb_web)/lib/java/httpcore-4.2.jar',
  #'$(lb_web)/lib/java/idpdiscovery.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-core-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-databind-2.1.1.jar',
  '$(lb_web)/lib/java/java-statsd-client.jar',
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
  #'$(lb_web)/lib/java/mail.jar',
  #'$(lb_web)/lib/java/openam-properties.jar',
  #'$(lb_web)/lib/java/openfedlib.jar',
  #'$(lb_web)/lib/java/opensso-sharedlib.jar',
  '$(lb_web)/lib/java/protobuf-2.5.0.jar',
  '$(lb_web)/lib/java/protobuf-java-format-1.3.jar',
  #'$(lb_web)/lib/java/rabbitmq-client.jar',
  #'$(lb_web)/lib/java/relaxngDatatype.jar',
  #'$(lb_web)/lib/java/s3lib-0.2.jar',
  #'$(lb_web)/lib/java/scala-library.jar',
  '$(lb_web)/lib/java/servlet-api-2.5.jar',
  '$(lb_web)/lib/java/webservices-api.jar',
  '$(lb_web)/lib/java/webservices-rt.jar'
  #'$(lb_web)/lib/java/xsdlib.jar'
]

jar(
   name = 'lb-steve-client',
   srcdir = 'java',
   classpath = classpath)

link_libs(classpath)

install_files(classpath, 'lib/java')

rule(
  output='install',
  input = [],
  commands = [
    'ln -sf lb-steve $(prefix)/bin/lb-steve-client',
  ]
)
