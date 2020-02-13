#! /usr/bin/env bash

image=$1
name=$2
size=$3
gcloud beta compute --project "manifest-canto-796" \
        instances create $name \
        --zone "us-central1-f" \
        --machine-type "n1-standard-2" \
        --network "default" \
        --metadata "ssh-rsa AAAAB3NzaC1yc2EAAAADAQABAAABAQDfWqNlFX5VwJ/hq5vcmL8x1gxfr8aRKhaV/G+2oB1OvMAqv8DihER61beE1rgYTgnGvq74PshqVGTtJT+gTF8JMxYvZ76A/VOazPHrsprZQULctBNC5jQiHZ1pfEH9MXIMhQIq4nFx3jkC8eQzGwNk4hYEAbyQ9atq2T+Ig/9Wt/a7xPD+58QpkIpRRhdUBjW4e6NueTxZBLhRWjkQkugW8eoE7K7tmeIJQRAfbG0nS5pu96eNHyiraBJZdX7ZG2cqV8KEHLh6urRKFkoOybvyZwP7P6iZ7OkSxPPK65gA7BUa7ZRNMfW6240cB9Wwx0ZHuNmzZaHhNO/SOBh9a621 ahmedsamti@TNTNLASAMTI19" \
        --no-restart-on-failure \
        --maintenance-policy "TERMINATE" \
        --preemptible \
        --service-account "lb-jobs-dev@lb-jobs.iam.gserviceaccount.com " \
        --scopes "https://www.googleapis.com/auth/devstorage.read_only","https://www.googleapis.com/auth/logging.write","https://www.googleapis.com/auth/monitoring.write","https://www.googleapis.com/auth/servicecontrol","https://www.googleapis.com/auth/service.management.readonly","https://www.googleapis.com/auth/trace.append" \
        --min-cpu-platform "Automatic" \
        --local-ssd interface="NVME" \
        --local-ssd interface="NVME" \
        --image $image \
        --image-project "manifest-canto-796" \
        --boot-disk-type "pd-ssd" \
        --boot-disk-device-name "worker"
