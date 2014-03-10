from lbconfig.api import *

lbconfig_package(
  'lb-steve-worker',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-worker',
  default_targets=['jars'])


s3lib_dep = (
  "s3lib", {'default_path': "/opt/logicblox/s3lib",
                  'help': "S3lib to use for this build."}
)
joda_time_dep = (
  "joda_time", {'default_path': "/opt/logicblox/joda-time"}
)
commons_exec_dep = (
  "commons_exec", {'default_path': "/opt/logicblox/commons-exec"}
)
protocols_dep = (
  "protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"}
)

depends_on(logicblox_dep, lb_web_dep, s3lib_dep, commons_exec_dep, protocols_dep, joda_time_dep)

bin_program('lb-steve-worker')

jar(
   name = 'lb-steve-worker',
   srcdir = 'java',
   classpath = [
     "$(protocols)/lib/java/lb-steve-protocols.jar",
     "$(logicblox)/lib/java/protobuf-2.5.0.jar",
     "$(lb_web)/lib/java/protobuf-java-format-1.3.jar",
     "$(s3lib)/lib/java/commons-io-2.4.jar",
     "$(s3lib)/lib/java/guava-15.0.jar",
     "$(s3lib)/lib/java/aws-java-sdk-1.7.1.jar",
     "$(s3lib)/lib/java/s3lib-0.2.jar"
   ])

install_dir('nix','nix')

rule(
  output='install',
  input = [],
  commands = [
    'cp -f $(s3lib)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(logicblox)/lib/java/protobuf-2.5.0.jar $(prefix)/lib/java',
    'cp -f $(lb_web)/lib/java/protobuf-java*.jar $(prefix)/lib/java',
    'cp -f $(protocols)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(commons_exec)/lib/java/*.jar $(prefix)/lib/java',
    'cp -f $(joda_time)/lib/java/*.jar $(prefix)/lib/java'
  ]
)

