#! /usr/bin/env bash

image=$1
name=$2
gcloud beta compute --project "lb-jobs" \
        instances create $name \
        --zone "us-central1-f" \
        --machine-type "n1-standard-2" \
        --network "default" \
        --metadata amine="ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIGLj6b2NxWaTh2epvC7DynHu//LKb8HOoXW03o2Q1DW8 amine@nixos" \
        --no-restart-on-failure \
        --maintenance-policy "TERMINATE" \
        --preemptible \
        --service-account "lb-jobs-dev@lb-jobs.iam.gserviceaccount.com" \
        --scopes "https://www.googleapis.com/auth/devstorage.read_only","https://www.googleapis.com/auth/logging.write","https://www.googleapis.com/auth/monitoring.write","https://www.googleapis.com/auth/servicecontrol","https://www.googleapis.com/auth/service.management.readonly","https://www.googleapis.com/auth/trace.append" \
        --min-cpu-platform "Automatic" \
        --local-ssd interface="NVME" \
        --local-ssd interface="NVME" \
        --image $image \
        --image-project "lb-jobs" \
        --boot-disk-type "pd-ssd" \
        --boot-disk-device-name "worker"
