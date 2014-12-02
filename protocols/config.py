from lbconfig.api import *

lbconfig_package(
  'lb-steve-protocols',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-protocols',
  default_targets=['jars', 'lb-libraries', 'java_protobufs'])

aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.9.8"})
s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})

depends_on(
  logicblox_dep,
  lb_web_dep,
  s3lib_dep,
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

protobuf_protocol(
  name = 'database',
  package = 'lb.steve',
  java_package = 'com.logicblox.steve.protocol',
  srcdir = 'proto'
)
# These 2 lines are a HACK to make this config file work with the older runtime version used by integration-modeler
# TODO - REMOVE THESE when integration-modeler is not needed anymore
depfile = java_protobuf_file('database', 'com.logicblox.steve.protocol')
rule(output='java_protobufs', input=depfile, phony=True)

lb_library(
  name='lb_steve_protocols',
  srcdir='proto'
)

classpath = [
  '$(logicblox)/lib/java/protobuf-2.5.0.jar',
  '$(logicblox)/lib/java/guava-15.0.jar',
  '$(logicblox)/lib/java/lb-common.jar',
  '$(s3lib)/lib/java/s3lib-0.2.jar',
  '$(aws)/lib/java/aws-java-sdk-1.9.8.jar',
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar'
]

jar(
   name = 'lb-steve-protocols',
   srcdir = 'java',
   srcgen = [
      java_protobuf_file('backend',  'com.logicblox.steve.protocol'),
      java_protobuf_file('frontend', 'com.logicblox.steve.protocol'),
      java_protobuf_file('database', 'com.logicblox.steve.protocol'),
   ],
   classpath = classpath)

link_libs(classpath)
