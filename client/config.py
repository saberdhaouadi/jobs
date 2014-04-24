from lbconfig.api import *

lbconfig_package(
  'lb-steve-client',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-client',
  default_targets=['jars'])

s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})
protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
  protocols_dep)

bin_program('lb-steve-client')

jar(
   name = 'lb-steve-client',
   srcdir = 'java',
   classpath = [
      '$(protocols)/lib/java/lb-steve-protocols.jar',
      '$(lb_web)/lib/java/lb-web-client.jar',
      '$(lb_web)/lib/java/protobuf-2.5.0.jar',
      '$(logicblox)/lib/java/guava-15.0.jar',
   ])

rule(
  output='install',
  input = [],
  commands = [
    'cp -f $(s3lib)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/lb-common.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/protobuf-2.5.0.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/protobuf-java*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-client-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-http-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-io-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-util-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/lb-web-client.jar $(prefix)/lib/java',
    'cp -f $(protocols)/lib/java/*.jar $(prefix)/lib/java',
  ]
)
