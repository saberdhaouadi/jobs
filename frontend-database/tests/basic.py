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


    def compare_jobs(self, expected, actual):
        '''
            Compares the expected and actual Job responses. This is useful because it compares the sets of
            attributes in an unordered fashion.
        '''
        expected_job = expected.response[0].job
        actual_job = actual.response[0].job
        self.assertEquals(expected_job.id, actual_job.id)
        self.assertEquals(expected_job.client_id, actual_job.client_id)
        self.assertEquals(expected_job.impl_id, actual_job.impl_id)
        self.assertEquals(expected_job.output_prefix, actual_job.output_prefix)
        self.assertEquals(expected_job.user_id, actual_job.user_id)
        self.assertEquals(expected_job.impl_archive, actual_job.impl_archive)

        self.assertMessageUnorderedEqual(expected_job.input, actual_job.input)
        self.assertMessageUnorderedEqual(expected_job.metadata, actual_job.metadata)
        self.assertMessageUnorderedEqual(expected_job.status, actual_job.status)
        self.assertMessageUnorderedEqual(expected_job.output, actual_job.output)


    def compare_job_impls(self, expected, actual):
        '''
            Compares the expected and actual JobImpl responses. This is useful because it compares the sets of
            attributes in an unordered fashion.
        '''
        expected_impls = expected.response[0].impl
        actual_impls = actual.response[0].impl
        self.assertEquals(len(expected_impls), len(actual_impls))
        for expected_impl, actual_impl in zip(sorted(expected_impls, key=lambda i:i.id), sorted(actual_impls, key=lambda i:i.id)):
            self.assertEquals(expected_impl.id, actual_impl.id)
            self.assertEquals(expected_impl.user_id, actual_impl.user_id)
            self.assertEquals(expected_impl.account_id, actual_impl.account_id)
            self.assertEquals(expected_impl.file.url, actual_impl.file.url)
            self.assertEquals(expected_impl.file.hash, actual_impl.file.hash)
            self.assertMessageUnorderedEqual(expected_impl.metadata, actual_impl.metadata)

    #
    # JOB IMPL TESTS
    #

    def test_set_job_impl(self):
        client = get_client("set_impl")
        envelope = client.dynamic_request()
        req = envelope.set_impl.add()
        req.impl_id = "total"
        req.user_id = "martin"
        req.file.url = "the url"
        req.file.hash = "the hash"
        m = req.metadata.add()
        m.key = "the key1"
        m.value = "the value1"
        m = req.metadata.add()
        m.key = "the key2"
        m.value = "the value2"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobimpls").get(), '''
            ID|ACCOUNT|USER|ARCHIVE|ARCHIVE_HASH
            total|logicblox.com|martin|the url|the hash
        ''')
        self.assertDelimEqual(get_tdx_client("jobimpl_metadata").get(), '''
            ID|ACCOUNT|KEY|VALUE
            total|logicblox.com|the key1|the value1
            total|logicblox.com|the key2|the value2
        ''')

    def test_get_job_impl(self):
        # make sure there's a jobimpl
        self.test_set_job_impl()

        client = get_client("get_impl")
        envelope = client.dynamic_request()
        req = envelope.get_impl.add()
        req.impl_id = "total"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              impl { id: "total" user_id: "martin" account_id: "logicblox.com" file { url: "the url" hash: "the hash" }
                metadata { key: "the key1" value: "the value1" }
                metadata { key: "the key2" value: "the value2" }
              }
            }
            ''', expected_response)
        self.compare_job_impls(expected_response, client.dynamic_call(envelope))



    def test_get_account_job_impls(self):
        # make sure there's a jobimpl
        self.test_set_job_impl()

        # add another in the same account
        client = get_client("set_impl")
        envelope = client.dynamic_request()
        req = envelope.set_impl.add()
        req.impl_id = "total2"
        req.user_id = "martin"
        req.file.url = "the url2"
        req.file.hash = "the hash2"
        client.dynamic_call(envelope)

        # add one in a different account
        envelope = client.dynamic_request()
        req = envelope.set_impl.add()
        req.impl_id = "total3"
        req.user_id = "jack"
        req.file.url = "the url3"
        req.file.hash = "the hash3"
        client.dynamic_call(envelope)

        # now get all implementations available to rob (same account as martin)
        client = get_client("get_impl")
        envelope = client.dynamic_request()
        req = envelope.get_impl.add()
        req.user_id = "rob"

        # verify response (should have martin's impls, but not jack's)
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              impl { id: "total" user_id: "martin" account_id: "logicblox.com" file { url: "the url" hash: "the hash" }
                metadata { key: "the key1" value: "the value1" }
                metadata { key: "the key2" value: "the value2" }
              }
              impl { id: "total2" user_id: "martin" account_id: "logicblox.com" file { url: "the url2" hash: "the hash2" } }
            }
            ''', expected_response)
        self.compare_job_impls(expected_response, client.dynamic_call(envelope))



    def test_update_job_impl(self):
        # make sure there's a jobimpl
        self.test_get_job_impl()

        # change some values using set_impl
        client = get_client("set_impl")
        envelope = client.dynamic_request()
        req = envelope.set_impl.add()
        req.impl_id = "total"
        req.user_id = "martin"
        req.file.url = "the url new"
        req.file.hash = "the hash"
        # change the value of key1
        m = req.metadata.add()
        m.key = "the key1"
        m.value = "the value1 new"
        # add key3, abandon key2
        m = req.metadata.add()
        m.key = "the key3"
        m.value = "the value3"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # check the status of the database
        client = get_client("get_impl")
        envelope = client.dynamic_request()
        req = envelope.get_impl.add()
        req.impl_id = "total"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              impl { id: "total" user_id: "martin" account_id: "logicblox.com" file { url: "the url new" hash: "the hash" }
                metadata { key: "the key1" value: "the value1 new" }
                metadata { key: "the key3" value: "the value3" }
              }
            }
            ''', expected_response)
        self.compare_job_impls(expected_response, client.dynamic_call(envelope))


    #
    # CREATE JOB TESTS
    #

    def test_create_job(self):
        # make sure there's a jobimpl
        self.test_set_job_impl()

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
        text_format.Merge('response { job_id: "1" }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total|s3://something/something|a
        ''')



    def test_create_job_with_data(self):
        # make sure there's a jobimpl
        self.test_set_job_impl()

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
        text_format.Merge('response { job_id: "1" }', expected_response)
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
        # make sure there's a jobimpl
        self.test_set_job_impl()

        client = get_client("create_job")
        envelope = client.dynamic_request()

        # note that we send job_id 2 and then 1
        req = envelope.create_job.add()
        req.job_id = "2"
        req.client_id = "a2"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something2"
        req.user_id = "martin"
        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a1"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something1"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('response { job_id: "2" } response { job_id: "1" }', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total|s3://something/something1|a1
            2|martin|total|s3://something/something2|a2
        ''')

    def test_create_job_with_errors(self):
        # make sure there's a jobimpl
        self.test_set_job_impl()

        client = get_client("create_job")
        envelope = client.dynamic_request()

        # note that we send job_id 2 and then 1
        req = envelope.create_job.add()
        req.job_id = "2"
        req.client_id = "a2"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something2"
        req.user_id = "martin"
        
        req = envelope.create_job.add()
        req.job_id = "3"
        req.client_id = "a3"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something3"
        req.user_id = "non_existent_user"

        req = envelope.create_job.add()
        req.job_id = "1"
        req.client_id = "a1"
        req.impl_id = "total"
        req.output_prefix = "s3://something/something1"
        req.user_id = "martin"

        req = envelope.create_job.add()
        req.job_id = "4"
        req.client_id = "a4"
        req.impl_id = "total4"
        req.output_prefix = "s3://something/something4"
        req.user_id = "martin"

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { job_id: "2" } 
            response { error { code: "NO_SUCH_USER" message: "User 'non_existent_user' does not exist." } } 
            response { job_id: "1" }
            response { error { code: "NO_SUCH_JOB_IMPL" message: "Job implementation 'total4' does not exist in account 'logicblox.com'." } }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("jobs").get(), '''
            ID|USER|JOBIMPL|OUTPUT_PREFIX|CLIENTID
            1|martin|total|s3://something/something1|a1
            2|martin|total|s3://something/something2|a2
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
            response { }
            response { error { code: "NO_SUCH_JOB" message: "Job \'wrong id\' does not exist." } }
            response { }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("job_status").get(), '''
            ID|TIMESTAMP|EVENT|MACHINE|MESSAGE
            1|1|the event|the machine|status message
            1|3|the event3|the machine3|status message3
        ''')

        # now use get status
        client = get_client("get_job")
        envelope = client.dynamic_request()
        req = envelope.get_job.add()
        req.job_id = "1"
        req.get_status = True
        req = envelope.get_job.add()
        req.job_id = "5"
        req.get_status = True

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              job { 
                id: "1"
                client_id: "a"
                impl_id: "total"
                output_prefix: "s3://something/something"
                user_id: "martin"
                impl_archive: "the url"
                status { timestamp: 3 event: "the event3" machine: "the machine3" message: "status message3" }
                status { timestamp: 1 event: "the event" machine: "the machine" message: "status message" } 
              }
            }
            response { error { code: "NO_SUCH_JOB" message: "Job '5' does not exist." } }
            ''', expected_response)
        self.compare_jobs(expected_response, client.dynamic_call(envelope))
        
    

    #
    # SET RESULT and GET RESULT TESTS
    #
    def test_set_get_result(self):
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
            response { }
            response { error { code: "NO_SUCH_JOB" message: "Job \'wrong id\' does not exist." } }
            ''', expected_response)
        self.assertMessageStringEqual(expected_response, client.dynamic_call(envelope))

        # verify data was imported
        self.assertDelimEqual(get_tdx_client("job_outputs").get(), '''
            ID|OUTPUT|HASH
            1|the url 1|the hash 1
            1|the url 2|the hash 2
        ''')

        # now use get result
        client = get_client("get_job")
        envelope = client.dynamic_request()
        req = envelope.get_job.add()
        req.job_id = "1"
        req.get_output = True
        req = envelope.get_job.add()
        req.job_id = "5"
        req.get_output = True

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              job { 
                id: "1"    
                client_id: "a"
                impl_id: "total"
                output_prefix: "s3://something/something"
                user_id: "martin"
                impl_archive: "the url"
                output { url: "the url 1" hash: "the hash 1" }
                output { url: "the url 2" hash: "the hash 2" }
              }
            }
            response { error { code: "NO_SUCH_JOB" message: "Job '5' does not exist." } }
            ''', expected_response)
        self.compare_jobs(expected_response, client.dynamic_call(envelope))



    def test_status_and_result(self):
        # create a simple job first
        self.test_create_job_with_data()

        # add some status entries
        client = get_client("add_status")
        envelope = client.dynamic_request()
        req = envelope.add_status.add()
        req.job_id = "1"
        req.status.timestamp = 1
        req.status.event = "the event1"
        req.status.machine = "the machine1"
        req.status.message = "status message1"

        req = envelope.add_status.add()
        req.job_id = "1"
        req.status.timestamp = 3
        req.status.event = "the event3"
        req.status.machine = "the machine3"
        req.status.message = "status message3"
        
        client.dynamic_call(envelope)

        # add a result
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

        client.dynamic_call(envelope)

        # now use get statuses and results
        client = get_client("get_job")
        envelope = client.dynamic_request()
        req = envelope.get_job.add()
        req.job_id = "1"
        req.get_metadata = True
        req.get_status = True
        req.get_input = True
        req.get_output = True

        # verify response
        expected_response = client.dynamic_response()
        text_format.Merge('''
            response { 
              job { 
                id: "1"
                client_id: "a"
                impl_id: "total"
                output_prefix: "s3://something/something"
                user_id: "martin"
                metadata { key: "the key2" value: "the value2" }
                metadata { key: "the key1" value: "the value1" }
                impl_archive: "the url"
                status { timestamp: 3 event: "the event3" machine: "the machine3" message: "status message3" }
                status { timestamp: 1 event: "the event1" machine: "the machine1" message: "status message1" } 
                output { url: "the url"   hash: "the hash" }
                output { url: "the url 1" hash: "the hash 1" }
                output { url: "the url 2" hash: "the hash 2" }
              }
            }
            ''', expected_response)
        self.compare_jobs(expected_response, client.dynamic_call(envelope))



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