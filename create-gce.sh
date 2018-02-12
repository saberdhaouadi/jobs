#! /bin/sh -e
set -x

build=$1

if [[ "$build" == "" ]]; then
    url=https://bob.logicblox.com/job/jobs/gcp-support/worker_image.gce/latest
    curl -o build.json -H 'Content-Type: application/json' -L -s $url
    build=$(jq -r .id build.json)
fi

curl -L https://bob.logicblox.com/build/$build/download-by-type/file/img > worker-gce.tar.gz

gsutil cp worker-gce.tar.gz gs://lb-jobs-testing/images/worker-gce-${build}.tar.gz
gcloud compute images create lb-jobs-$build --source-uri gs://lb-jobs-testing/images/worker-gce-${build}.tar.gz
