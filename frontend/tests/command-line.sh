#! /usr/bin/env bash

set -e
set -u
set -x
set -o pipefail

scriptdir=$(readlink -f $(dirname $BASH_SOURCE))
topdir=$scriptdir/../..

#####################################################
# Start servers before running tests
function start_servers()
{
    killall java || echo "failure okay"

    lb-steve-frontend --config ./frontend.config &> frontend.log &
    frontend_pid=$!
    # pure evil, but okay
    sleep 3
    
    export NIX_PATH=worker=$topdir/worker-minimal:$NIX_PATH
    lb-steve-worker --incoming $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '1q;d') \
                    --outgoing $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '3q;d') \
                    &> worker1.log &
    worker1_pid=$!

    lb-steve-worker --incoming $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '2q;d') \
                    --outgoing $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '3q;d') \
                    &> worker2.log &
    worker2_pid=$!

    # Job implementations used by various tests
    tar czvf fail.tar.gz -C $topdir/sample-jobs fail
    lb-steve-client upload-impl --impl fail -i fail.tar.gz --wait

    tar czvf identity.tar.gz -C $topdir/sample-jobs identity
    lb-steve-client upload-impl --impl identity -i identity.tar.gz --wait

    tar czvf total.tar.gz -C $topdir/sample-jobs total
    lb-steve-client upload-impl --impl total -i total.tar.gz --wait
}

#####################################################
# Stop servers
function stop_servers()
{
    kill $frontend_pid
    kill $worker1_pid
    kill $worker2_pid
}

#####################################################
# Test that the server now has two implementations available, and
# verify the metadata.
function test_upload_impl()
{
    test "$(lb-steve-client list-impl | grep total | jq -c '[.id]')" \
        = '["total"]'

    # Check basics of uploading job implementations
    lb-steve-client upload-impl --impl total-v1 -i total.tar.gz --metadata revision=1 another=bar
    lb-steve-client upload-impl --impl total-v2 -i total.tar.gz --metadata revision=2 another=foo

    lb-steve-client list-impl
    test $(lb-steve-client list-impl | grep total | wc --lines) \
        = "3"
    test "$(lb-steve-client list-impl | grep total-v1 | jq -c '[.id, .revision, .another]')" \
        = '["total-v1","1","bar"]'
    test "$(lb-steve-client list-impl | grep total-v2 | jq -c '[.id, .revision, .another]')" \
        = '["total-v2","2","foo"]'
}

#####################################################
# Test that uploading a job implementation that does not exist gives a proper error
function test_upload_impl_no_file()
{
    test "$(lb-steve-client upload-impl --impl foo -i s3://steve-jobs/does-not-exist 2>&1 \
        | head -1 | jq -c '[.error_code, .http_status]')" \
     = '["FILE_NOT_FOUND",400]'
}

#####################################################
# Test that executing a job for a non-existing implementation gives a
# proper error.
function test_create_job_wrong_impl()
{
    test "$(lb-steve-client create-job --impl does-not-exist -o ./foo 2>&1 \
         | head -1 | jq -c '[.error_code, .http_status]')" \
         = '["NO_SUCH_JOB_IMPL",400]'
}

#####################################################
# Test that executing a job for a non-existing queue gives a proper
# error.
function test_create_job_wrong_queue()
{
    test "$(lb-steve-client create-job --impl identity -o ./foo --queue does-not-exist 2>&1 \
         | head -1 | jq -c '[.error_code, .http_status]')" \
         = '["NO_SUCH_JOB_QUEUE",400]'
}

#####################################################
# Test that asking for the status/result of a non-existing job gives a proper error
function test_status_no_such_job()
{
    test "$(lb-steve-client status does-not-exist 2>&1 \
            | head -1 | jq -c '[.error_code, .http_status]')" \
        = '["NO_SUCH_JOB",400]'

    test "$(lb-steve-client output does-not-exist 2>&1 \
            | head -1 | jq -c '[.error_code, .http_status]')" \
        = '["NO_SUCH_JOB",400]'
}

#####################################################
# Test executing a simple job and wait for the result
function test_create_job_wait()
{
    rm -f input.txt
    rm -f output.txt
    rm -rf test-input-data
    rm -rf test-output-data

    # Test file input/output
    seq 100 > input.txt
    lb-steve-client create-job --impl total -i input.txt -o output.txt --wait
    test "$(cat output.txt)" = "5050"

    # Test directory input/output
    mkdir test-input-data
    echo "a" > test-input-data/a.txt
    echo "b" > test-input-data/b.txt
    echo "c" > test-input-data/c.txt
    lb-steve-client create-job --impl identity -i test-input-data -o test-output-data --wait
    test "$(cat test-output-data/a.txt)" = "a"
    test "$(cat test-output-data/b.txt)" = "b"
    test "$(cat test-output-data/c.txt)" = "c"

    # Test file input/output to specific queue
    seq 100 > input.txt
    lb-steve-client create-job --impl total -i input.txt -o output.txt --queue large --wait
    test "$(cat output.txt)" = "5050"
}

#####################################################
# Test executing a simple job
function test_create_job()
{
    rm -f input.txt
    rm -f output.txt

    seq 101 > input.txt
    local job_id=$(lb-steve-client create-job --impl total -i ./input.txt | jq -r -c '.job_id')

    # Immediately asking for the output gives a bad request
    test "$(lb-steve-client output $job_id  2>&1 \
        | head -1 | jq -c '[.error_code, .http_status]')" \
        = '["JOB_INCOMPLETE",400]'

    lb-steve-client status $job_id
    lb-steve-client output $job_id --wait

    # Waiting twice is fine ...
    lb-steve-client output $job_id --wait

    # Asking for status again is fine ...
    lb-steve-client status $job_id

    # Make sure state line contains SUCCEEEDED
    lb-steve-client status $job_id | head -1 | grep SUCCEEDED

    # Download the output is allowed at any point in time
    lb-steve-client output $job_id -o output.txt
    test "$(cat output.txt)" = "5151"

    # Separately downloading the output is fine too
    url=$(lb-steve-client output $job_id | jq -r -c '.url')

    rm -f output.txt
    s3tool download $url -o output.txt
    test "$(cat output.txt)" = "5151"
}

#####################################################
# Test executing a job that always fails
function test_create_job_fail()
{
    local job_id=$(lb-steve-client create-job --impl fail | jq -r -c '.job_id')

    # Wait for completion and make sure we report that job failed
    test "$(lb-steve-client output $job_id --wait 2>&1 \
        | tail -1 | jq -c '[.error_code]')" \
        = '["JOB_FAILED"]'

    # Make sure we also report failure if not waiting
    ! lb-steve-client output $job_id

    # Make sure we also report failure if waiting again
    ! lb-steve-client output $job_id --wait
    
    # Asking for status succeeds even if the job failed
    lb-steve-client status $job_id

    # Create+wait also has exit code 1 when job fails
    ! lb-steve-client create-job --impl fail --wait
}

start_servers
trap stop_servers EXIT

test_upload_impl
test_upload_impl_no_file
test_create_job_wrong_impl
test_create_job_wrong_queue
test_status_no_such_job
test_create_job_wait
test_create_job
test_create_job_fail

echo "****************** SUCCESS *******************"
