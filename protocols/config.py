from lbconfig.api import *

lbconfig_package(
  'lb-steve-protocols',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-protocols',
  default_targets=['jars'])

aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.7.1"})

depends_on(
  logicblox_dep,
  aws_dep
)

protobuf_protocol(
  name = 'backend',
  package = 'lb.steve',
  java_package = 'com.logicblox.steve.protocol',
  srcdir = 'proto',
  gen_datalog=False
)

protobuf_protocol(
  name = 'frontend',
  package = 'lb.steve',
  java_package = 'com.logicblox.steve.protocol',
  srcdir = 'proto',
  gen_datalog=False
)

jar(
   name = 'lb-steve-protocols',
   srcdir = 'java',
   srcgen = [
      java_protobuf_file('backend', 'com.logicblox.steve.protocol'),
      java_protobuf_file('frontend', 'com.logicblox.steve.protocol'),
   ],
   classpath = [
      '$(logicblox)/lib/java/protobuf-2.5.0.jar',
      '$(logicblox)/lib/java/guava-15.0.jar',
      '$(logicblox)/lib/java/lb-common.jar',
      '$(aws)/lib/java/aws-java-sdk-1.7.1.jar',
   ])
