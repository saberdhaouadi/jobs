#! /usr/bin/env bash

image=$1
name=$2
size=$3
gcloud beta compute --project "manifest-canto-796" \
        instances create $name \
        --zone "us-central1-f" \
        --machine-type "n1-standard-2" \
        --network "default" \
        --metadata "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQCunr4txUxeXVeaEkLm06vjFceW71ciwf3vPtGQNRPa3mRIxWxRvtaSXj8djNn9g9Lc/Rqjhz2LuGfi9rQVeynpglmicSmt6Ge3UpQL+Z4QibY95movUTb+yvjIFTOHGbeRBGholpfvCK1vd/ZCzv9/21X2Mbg8N1X2/pxGdsmtv6dG9tOuF4Bv47uZA4pzMUC16XxriJN9WKBcrUwv5tPqP0uQoSWnnuU/RIMnZIiZUxi16jKTdMWRUFjx69s/lHkgUdnkAim7ZahhWOCsFAQTq65RdNsi40c/6N7MenWIWWiPIqQ59VpV7E9sxXa4Kbj7W/v4wqEzTcOFuG3EHuGx ahmed.samti@infor.com" \
        --no-restart-on-failure \
        --maintenance-policy "TERMINATE" \
        --preemptible \
        --service-account "716753782997-compute@developer.gserviceaccount.com" \
        --scopes "https://www.googleapis.com/auth/devstorage.read_only","https://www.googleapis.com/auth/logging.write","https://www.googleapis.com/auth/monitoring.write","https://www.googleapis.com/auth/servicecontrol","https://www.googleapis.com/auth/service.management.readonly","https://www.googleapis.com/auth/trace.append" \
        --min-cpu-platform "Automatic" \
        --local-ssd interface="NVME" \
        --local-ssd interface="NVME" \
        --image $image \
        --image-project "manifest-canto-796" \
        --boot-disk-type "pd-ssd" \
        --boot-disk-device-name "worker"
