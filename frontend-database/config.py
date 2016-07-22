from lbconfig.api import *
import lbconfig.core

protocols_dep = ("protocols", {'default_path': "/opt/logicblox/lb-steve-protocols"})

lbconfig_package(
  'lb-steve-frontend-database',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend-database',
  default_targets=['lb-libraries']
)

depends_on(logicblox_dep, lb_web_dep, protocols_dep)

lb_library(
    name='lb_steve_frontend_database',
    srcdir='logic',
    deps={'lb_web': '$(lb_web)', 'lb_steve_protocols': '$(protocols)'}
)

install_file('scripts/install.sh', '')
install_file('scripts/profile_disk_bg.sh', '')

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

check_lb_workspace(
    name='lb-steve-frontend-database-test',
    libraries=['lb_steve_frontend_database']
)

check_program('tests/basic.py', ['lb-steve-frontend-database-test'])

import subprocess
if subprocess.check_output(["lb", "version"]).strip() != "4.1.7":
    install_dir('$(protocols)/share/lb-steve-protocols/lb_steve_protocols', 'share/lb-steve-protocols/lb_steve_protocols')
else:
    install_dir('$(protocols)/share/lb_steve_protocols', 'share/lb_steve_protocols')

install_dir('workflows', 'workflows')
