from lbconfig.api import *

lbconfig_package(
  'lb-steve-worker',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-worker',
  default_targets=['jars'])

depends_on(
  logicblox_dep,
  lb_web_dep)

bin_program('lb-steve-worker')

jar(
   name = 'lb-steve-worker',
   srcdir = 'java',
   classpath = [
      '$(lb_web)/lib/java/lb-web-server.jar',
      '$(lb_web)/lib/java/lb-web-client.jar',
      '$(lb_web)/lib/java/protobuf-2.5.0.jar',
      '$(lb_web)/lib/java/servlet-api-2.5.jar',
      '$(lb_web)/lib/java/jetty-http-7.6.7.v20120910.jar',
      '$(logicblox)/lib/java/guava-15.0.jar',
   ])
