from lbconfig.api import *

lbconfig_package(
  'lb-steve-client',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-client',
  default_targets=['jars'])

s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})
protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.7.1"})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
  aws_dep,
  protocols_dep)

bin_program('lb-steve-client')
config_file('config/lb-steve-client.config')

jar(
   name = 'lb-steve-client',
   srcdir = 'java',
   classpath = [
      '$(aws)/lib/java/aws-java-sdk-1.7.1.jar',
      '$(s3lib)/lib/java/s3lib-0.2.jar',
      '$(protocols)/lib/java/lb-steve-protocols.jar',
      '$(lb_web)/lib/java/lb-web-client.jar',
      '$(lb_web)/lib/java/protobuf-2.5.0.jar',
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
    'cp -f $(s3lib)/lib/java/s3lib-0.2.jar $(prefix)/lib/java',
    'cp -f $(s3lib)/lib/java/log4j* $(prefix)/lib/java',
    'cp -f $(s3lib)/lib/java/jcommander* $(prefix)/lib/java',
    'cp -f $(s3lib)/lib/java/guava* $(prefix)/lib/java',
    'cp -f $(s3lib)/lib/java/commons-codec-*.jar $(prefix)/lib/java',
    'cp -f $(s3lib)/lib/java/commons-io-*.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/lb-common.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/protobuf-2.5.0.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/protobuf-java*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/gson*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-client-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-http-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-io-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/jetty-util-*.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/lb-web-client.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/commons-configuration-* $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/commons-lang-* $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/commons-logging-* $(prefix)/lib/java',
    'cp -f $(protocols)/lib/java/*.jar $(prefix)/lib/java',
    'ln -sf lb-steve $(prefix)/bin/lb-steve-client',
  ]
)
