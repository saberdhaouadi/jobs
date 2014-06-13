#! /usr/bin/env bash

set -e
set -u
set -x

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

tar czvf total.tar.gz -C $topdir/sample-jobs total
lb-steve-client upload-impl --impl total-v1 -i total.tar.gz
lb-steve-client list-impl
