#! /usr/bin/env bash

image=$1
name=$2
size=$3
gcloud beta compute --project "manifest-canto-796" \
        instances create $name \
        --zone "us-central1-f" \
        --machine-type "n1-standard-2" \
        --network "default" \
        --metadata "sshKeys=root:ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDGdFLqvogt79Gj+8oiqHK7f+NqnVV/d8AN1nMImk+IoK+IqVcOh434v8UNEH3YqbFOVy1eFkbpD9oSeYkdANZAWYXQyqYgD27jG9zOCXfNIMrsZhbB83pAEGVhiRKCdajrEF4L9z3/25u3nCBRFnQDsp62CjMZ3P7LHfLnzLPI/wghF/vnr3icDXm8CNVW3H+8fdFUshjIvOtWyJ25zpYJ2RldcM6DmZkwH2UbNoWWuMKzEM/dDoJ5rLHlMF/+F+bSSTGdn1U5Oc6iM03b6+hPUg6nT4nMeoR99yQQXvIY01IKPaDZQ3UqTGh5HgkLqTTD2T5HkYIpcyX57LQBj81x aminechikhaoui@PDXL0711-LatE6530" \
        --no-restart-on-failure \
        --maintenance-policy "TERMINATE" \
        --preemptible \
        --service-account "716753782997-compute@developer.gserviceaccount.com" \
        --scopes "https://www.googleapis.com/auth/devstorage.read_only","https://www.googleapis.com/auth/logging.write","https://www.googleapis.com/auth/monitoring.write","https://www.googleapis.com/auth/servicecontrol","https://www.googleapis.com/auth/service.management.readonly","https://www.googleapis.com/auth/trace.append" \
        --min-cpu-platform "Automatic" \
        --local-ssd interface="NVME" \
        --image $image \
        --image-project "manifest-canto-796" \
        --boot-disk-size $size \
        --boot-disk-type "pd-ssd" \
        --boot-disk-device-name "worker"
