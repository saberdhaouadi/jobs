from lbconfig.api import *

lbconfig_package(
  'lb-steve-frontend',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend',
  default_targets=['jars'])

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib"})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.7.1"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
  aws_dep,
  protocols_dep)

bin_program('lb-steve-frontend')
config_file('config/lb-steve-frontend.config')
config_file('config/steve_service_config.json')

jar(
   name = 'lb-steve-frontend',
   srcdir = 'java',
   classpath = [
      '$(protocols)/lib/java/lb-steve-protocols.jar',
      '$(lb_web)/lib/java/lb-web-server.jar',
      '$(lb_web)/lib/java/lb-web-client.jar',
      '$(lb_web)/lib/java/protobuf-2.5.0.jar',
      '$(lb_web)/lib/java/servlet-api-2.5.jar',
      '$(lb_web)/lib/java/jetty-http-7.6.7.v20120910.jar',
      '$(logicblox)/lib/java/guava-15.0.jar',
   ])

rule(
  output='install',
  input = [],
  commands = [
    'cp -f $(aws)/lib/java/jackson*.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/aws-java-sdk-1.7.1.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/http*.jar $(prefix)/lib/java',
    'cp -f $(aws)/lib/java/joda-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/config/lb-web-server.config $(prefix)/config',
    'cp -f $(protocols)/lib/java/*.jar $(prefix)/lib/java',
  ]
)
