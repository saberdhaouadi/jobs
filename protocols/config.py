from lbconfig.api import *

lbconfig_package(
  'lb-steve-protocols',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-protocols',
  default_targets=['jars'])

depends_on(
  logicblox_dep
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
      '$(logicblox)/lib/java/protobuf-2.5.0.jar'
   ])

