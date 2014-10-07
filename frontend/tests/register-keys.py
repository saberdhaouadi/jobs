#!/usr/bin/env python
import os
import sys

lbpath =  os.path.join(os.environ.get('LB_WEBSERVER_HOME'), 'lib', 'python')
sys.path.insert(0, lbpath)

import lb.web.credentials

credentials_client = lb.web.credentials.Client()
credentials_client.set_public_key("martin", '../testdata/martin.pub', create = True)
credentials_client.set_public_key("rob", '../testdata/rob.pub', create = True)
