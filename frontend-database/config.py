from lbconfig.api import *

lbconfig_package(
  'lb-steve-frontend-database',
  version='1.0',
  default_prefix='/opt/logicblox/lb-steve-frontend-database',
  default_targets=['lb-libraries'])

depends_on(logicblox_dep, lb_web_dep)

lb_library(
    name='lb_steve_frontend_database',
    srcdir='logic',
    deps={'lb_web': '$(lb_web)'})

check_lb_workspace(
    name='lb-steve-frontend-test',
    libraries=['lb_steve_frontend_database'])

