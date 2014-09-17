#! /usr/bin/env python

import sys
import os
try:
    import unittest2 as unittest
except ImportError:
    import unittest

sys.path.insert(0, '%s/lib/python' % os.environ.get('LOGICBLOX_HOME'))
sys.path.insert(0, '%s/lib/python' % os.environ.get('LB_WEBSERVER_HOME'))

import lb.web.testcase
import lb.web.service
import lb.web.admin

class TestFrontendDatabase(lb.web.testcase.PrototypeWorkspaceTestCase):

    prototype = 'lb-steve-frontend-test'

    def setUp(self):
        super(TestFrontendDatabase, self).setUp()
        self.client = lb.web.service.Client("localhost", 8080, "/time")

    def test_create_job(self, client):
        req = client.dynamic_request()
        req.id = "1"
        req.clientid = "a"
        req.impl = "total"
        req.output_prefix = "s3://something/something"

        response = client.dynamic_call(req)
        self.assertHasField(response, "answer")

def suite(args):
    suite = unittest.TestSuite()
    suite.addTest(unittest.makeSuite(TestFrontendDatabase))
    return suite

if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(suite(sys.argv))
    sys.exit(not result.wasSuccessful())
