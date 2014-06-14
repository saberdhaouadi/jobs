#! /usr/bin/env bash

set -e
set -u
set -x
set -o pipefail

scriptdir=$(readlink -f $(dirname $BASH_SOURCE))
topdir=$scriptdir/../..

function start_servers()
{
  killall java || echo "failure okay"

  lb-steve-frontend --config ./frontend.config &
  frontend_pid=$!
  # pure evil, but okay
  sleep 3

  export NIX_PATH=worker=$topdir/worker-minimal:$NIX_PATH
  lb-steve-worker --incoming $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '1q;d') \
                  --outgoing $(cat $scriptdir/frontend.config | awk '$1 == "sqs_queue_url" {print $3}' | sed '2q;d') &
  worker_pid=$!
}

function stop_servers()
{
  kill $frontend_pid
  kill $worker_pid
}

start_servers
trap stop_servers EXIT

# Check basics of uploading job implementations

tar czvf total.tar.gz -C $topdir/sample-jobs total
lb-steve-client upload-impl --impl total-v1 -i total.tar.gz --metadata revision=1 another=bar
lb-steve-client upload-impl --impl total-v2 -i total.tar.gz --metadata revision=2 another=foo

lb-steve-client list-impl
test $(lb-steve-client list-impl | wc --lines) = "2"
test "$(lb-steve-client list-impl | grep total-v1 | jq -c '[.id, .revision, .another]')" = '["total-v1","1","bar"]'
test "$(lb-steve-client list-impl | grep total-v2 | jq -c '[.id, .revision, .another]')" = '["total-v2","2","foo"]'

seq 100 > data.txt
job_id=$(lb-steve-client create-job --impl total-v1 \
             -i ./data.txt -o s3://steve-jobs/data/total/output | jq -r -c '.job_id')

lb-steve-client status $job_id
sleep 10

lb-steve-client status $job_id
sleep 10

lb-steve-client status $job_id
sleep 10

lb-steve-client output $job_id

echo "****************** SUCCESS *******************"
