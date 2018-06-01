#!/usr/bin/env python
import os
import sys

LB_WEBSERVER_HOME = os.environ.get('LB_WEBSERVER_HOME')

sys.path.insert(0, '%s/lib/python' % os.environ.get('LOGICBLOX_HOME'))
sys.path.insert(0, '%s/lib/python' % LB_WEBSERVER_HOME)

import lb.web.credentials

credentials_client = lb.web.credentials.Client()
credentials_client.set_public_key("martin", '../testdata/martin.pub', create = True)
credentials_client.set_public_key("rob", '../testdata/rob.pub', create = True)
