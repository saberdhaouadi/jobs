from lbconfig.api import *

lbconfig_package(
  'lb-steve-protocols',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-protocols',
  default_targets=['jars'])

aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.7.1"})
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

classpath = [
  '$(logicblox)/lib/java/protobuf-2.5.0.jar',
  '$(logicblox)/lib/java/guava-15.0.jar',
  '$(logicblox)/lib/java/lb-common.jar',
  '$(s3lib)/lib/java/s3lib-0.2.jar',
  '$(aws)/lib/java/aws-java-sdk-1.7.1.jar',
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
