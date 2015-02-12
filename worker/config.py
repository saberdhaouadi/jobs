from lbconfig.api import *

lbconfig_package(
  'lb-steve-worker',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-worker',
  default_targets=['jars'])


s3lib_dep = ("s3lib", {'default_path': "/opt/logicblox/s3lib", 'help': "S3lib to use for this build."})
aws_dep = ("aws", {'default_path': "/opt/logicblox/deps/aws-java-sdk-1.9.8"})

commons_exec_dep = (
  "commons_exec", {'default_path': "/opt/logicblox/deps/commons-exec-1.2"}
)
commons_cli_dep = (
  "commons_cli", {'default_path': "/opt/logicblox/deps/commons-cli-1.2"}
)
protocols_dep = (
  "protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"}
)

depends_on(
    logicblox_dep,
    lb_web_dep,
    s3lib_dep,
    aws_dep,
    commons_exec_dep,
    protocols_dep,
    commons_cli_dep)

bin_program('lb-steve-worker')
bin_program('lb-steve-provisioner')

classpath = [
  '$(protocols)/lib/java/lb-steve-protocols.jar',

  '$(aws)/lib/java/aws-java-sdk-1.9.8.jar',
  '$(aws)/lib/java/joda-time-2.2.jar',

  '$(s3lib)/lib/java/jcommander-1.29.jar',
  '$(s3lib)/lib/java/commons-io-2.4.jar',
  '$(s3lib)/lib/java/guava-15.0.jar',
  '$(s3lib)/lib/java/s3lib-0.2.jar',
  
  '$(commons_exec)/lib/java/commons-exec.jar',
  '$(commons_cli)/lib/java/commons-cli.jar',
  
  '$(lb_web)/lib/java/commons-codec-1.9.jar',
  '$(lb_web)/lib/java/commons-logging-1.1.1.jar',
  '$(lb_web)/lib/java/protobuf-java-format-1.3.jar',
  '$(lb_web)/lib/java/gson-2.2.4.jar',  
  '$(lb_web)/lib/java/lb-web-client.jar',
  '$(lb_web)/lib/java/lb-web-server.jar',
  '$(lb_web)/lib/java/httpclient-4.2.3.jar',
  '$(lb_web)/lib/java/httpcore-4.2.jar',
  '$(lb_web)/lib/java/jetty-client-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-continuation-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-http-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-io-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-security-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-server-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-servlet-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jetty-util-7.6.7.v20120910.jar',
  '$(lb_web)/lib/java/jackson-annotations-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-core-2.1.1.jar',
  '$(lb_web)/lib/java/jackson-databind-2.1.1.jar',
  '$(lb_web)/lib/java/protobuf-2.5.0.jar',
  '$(lb_web)/lib/java/lb-common.jar',
  '$(lb_web)/lib/java/lb-common-protocol.jar',
]

jar(
   name = 'lb-steve-worker',
   srcdir = 'java',
   classpath = classpath)

install_dir('nix','nix')

link_libs(classpath)

install_files(classpath, 'lib/java')
