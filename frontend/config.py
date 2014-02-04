from lbconfig.api import *

lbconfig_package(
  'lb-steve-frontend',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend',
  default_targets=['jars'])

depends_on(
  logicblox_dep,
  lb_web_dep)

protobuf_protocol(
  name = 'frontend',
  package = 'lb.steve',
  java_package = 'com.logicblox.steve',
  srcdir = 'proto',
  gen_datalog=False
)

bin_program('lb-steve-frontend')

jar(
   name = 'lb-steve-frontend',
   srcdir = 'java',
   srcgen = [
      java_protobuf_file('frontend', 'com.logicblox.steve'), 
   ],
   classpath = [
      '$(lb_web)/lib/java/lb-web-server.jar',
      '$(lb_web)/lib/java/lb-web-client.jar',
      '$(lb_web)/lib/java/protobuf-2.5.0.jar',
      '$(lb_web)/lib/java/servlet-api-2.5.jar',
      '$(lb_web)/lib/java/jetty-http-7.6.7.v20120910.jar',
      '$(logicblox)/lib/java/guava-15.0.jar',
   ])
