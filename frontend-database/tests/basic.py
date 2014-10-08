#! /usr/bin/env python

import sys
import os
try:
    import unittest2 as unittest
except ImportError:
    import unittest

sys.path.insert(0, '%s/lib/python' % os.environ.get('LOGICBLOX_HOME'))
sys.path.insert(0, '%s/lib/python' % os.environ.get('LB_WEBSERVER_HOME'))

from google.protobuf import text_format

import lb.web.testcase
import lb.web.service
import lb.web.admin

def get_client(path):
    return lb.web.service.Client("localhost", 8080, "/db/" + path)

def get_tdx_client(path):
    return lb.web.service.DelimClient("localhost", 8080, "/tdx/" + path)

class TestFrontendDatabase(lb.web.testcase.PrototypeWorkspaceTestCase):

    prototype = 'lb-steve-frontend-test'

    def setUp(self):
        super(TestFrontendDatabase, self).setUp()
        # import some test users
        delim = lb.web.service.DelimClient("localhost", 8080, "/tdx/users")
        with open ("tests/users.csv", "r") as f:
          delim.post(f.read())

    #
    # CREATE JOB TESTS
    #

    def test_create_job(self):
        client = get_client("create_job")
        envelope = client.dynamic_request()
        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { job { id: "1" } }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total|s3://something/something|a
        ''')



    def test_create_job_with_data(self):
        client = get_client("create_job")
        envelope = client.dynamic_request()
        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something"
        req.user_id = "martin"
        f = req.input.add()
        f.url = "the url"
        f.hash = "the hash"
        m = req.metadata.add()
        m.key = "the key1"
        m.value = "the value1"
        m = req.metadata.add()
        m.key = "the key2"
        m.value = "the value2"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { job { id: "1" } }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total|s3://something/something|a
        ''')

        self.assertDelimEqual(get_tdx_client("job_inputs").get(), '''
            ID|INPUT|HASH
            1|the url|the hash
        ''')

        self.assertDelimEqual(get_tdx_client("job_metadata").get(), '''
            ID|KEY|VALUE
            1|the key1|the value1
            1|the key2|the value2
        ''')

    def test_create_job_multiple(self):
        client = get_client("create_job")
        envelope = client.dynamic_request()

        # note that we send job_id 2 and then 1
        req = envelope.create_job.add()
        req.job_id = "2"
        req.client_id = "a2"
        req.impl_id = "total2"
        req.output_prefix = "s3://something/something2"
        req.user_id = "martin"
        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a1"
        req.impl_id = "total1"
        req.output_prefix = "s3://something/something1"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { job { id: "2" } } response { job { id: "1" } }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total1|s3://something/something1|a1
            2|martin|total2|s3://something/something2|a2
        ''')

    def test_create_job_with_errors(self):
        client = get_client("create_job")
        envelope = client.dynamic_request()

        # note that we send job_id 2 and then 1
        req = envelope.create_job.add()
        req.job_id = "2"
        req.client_id = "a2"
        req.impl_id = "total2"
        req.output_prefix = "s3://something/something2"
        req.user_id = "martin"
        
        req = envelope.create_job.add()
        req.job_id = "3"
        req.client_id = "a3"
        req.impl_id = "total3"
        req.output_prefix = "s3://something/something3"
        req.user_id = "non_existent_user"

        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a1"
        req.impl_id = "total1"
        req.output_prefix = "s3://something/something1"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { job { id: "2" } } 
            response { error { code: "INVALID_USER" message: "User identified by \'non_existent_user\' does not exist." } } 
            response { job { id: "1" } }''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total1|s3://something/something1|a1
            2|martin|total2|s3://something/something2|a2
        ''')

 
    #
    # ADD STATUS and GET STATUS TESTS
    #

    def test_add_get_status(self):
        # create a simple job first
        self.test_create_job()

        client = get_client("add_status")
        envelope = client.dynamic_request()
        req = envelope.add_status.add()
        req.job_id = "1"
        req.status.timestamp = 1
        req.status.event = "the event"
        req.status.machine = "the machine"
        req.status.message = "status message"

        req = envelope.add_status.add()
        req.job_id = "wrong id"
        req.status.timestamp = 2
        req.status.event = "the event2"
        req.status.machine = "the machine2"
        req.status.message = "status message2"

        req = envelope.add_status.add()
        req.job_id = "1"
        req.status.timestamp = 3
        req.status.event = "the event3"
        req.status.machine = "the machine3"
        req.status.message = "status message3"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { job { id: "1" } }
            response { error { code: "INVALID_JOB" message: "Job identified by \'wrong id\' does not exist." } }
            response { job { id: "1" } }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("job_status").get(), '''
            ID|TIMESTAMP|EVENT|MACHINE|MESSAGE
            1|1|the event|the machine|status message
            1|3|the event3|the machine3|status message3
        ''')

        """ TODO - not working yet. Not sure how to use the support block from after_fixpoint blocks.
        # now use get status
        client = get_client("get_status")
        envelope = client.dynamic_request()
        req = envelope.get_status.add()
        req.job_id = "5"

        # verify response
        print(client.dynamic_call(envelope))
        expected_response = client.dynamic_response()
        text_format.Merge('''
            //response { job { id: "1" } }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))
        """


    #
    # SET RESULT TESTS
    #

    def test_set_result(self):
        # create a simple job first
        self.test_create_job()

        client = get_client("set_result")
        envelope = client.dynamic_request()
        req = envelope.set_result.add()
        req.job_id = "1"
        f = req.output.add()
        f.url = "the url 1"
        f.hash = "the hash 1"
        f = req.output.add()
        f.url = "the url 2"
        f.hash = "the hash 2"

        req = envelope.set_result.add()
        req.job_id = "wrong id"
        f = req.output.add()
        f.url = "the url 3"
        f.hash = "the hash 3"
        f = req.output.add()
        f.url = "the url 4"
        f.hash = "the hash 4"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { job { id: "1" } }
            response { error { code: "INVALID_JOB" message: "Job identified by \'wrong id\' does not exist." } }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("job_outputs").get(), '''
            ID|OUTPUT|HASH
            1|the url 1|the hash 1
            1|the url 2|the hash 2
        ''')


def suite(args):
    suite = unittest.TestSuite()
    if (len(args) == 1):
        suite.addTest(unittest.makeSuite(TestFrontendDatabase))
    else:
        suite.addTest(TestFrontendDatabase(args[1]))
    return suite

if __name__ == '__main__':
    result = unittest.TextTestRunner(verbosity=2).run(suite(sys.argv))
    sys.exit(not result.wasSuccessful())