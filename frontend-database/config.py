from lbconfig.api import *

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})

lbconfig_package(
  'lb-steve-frontend-database',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend-database',
  default_targets=['lb-libraries'])

depends_on(logicblox_dep, lb_web_dep, protocols_dep)

lb_library(
    name='lb_steve_frontend_database',
    srcdir='logic',
    deps={'lb_web': '$(lb_web)'})

check_lb_workspace(
    name='lb-steve-frontend-test',
    libraries=['lb_steve_frontend_database'])

check_program('tests/basic.py', ['lb-steve-frontend-test'])

install_file('scripts/install.sh', '')

rule(
    output = 'deploy',
    input = [ 'install' ],
    commands = [
      'cd $(prefix) ; ./install.sh ; lb web-server load-services -w lb-steve',
      ''
    ],
    description = '',
    phony = True
)

link_libs([])

