#! /usr/bin/env bash
set -u
out="$1"

while true
do
  echo "##########" >> $out
  echo "date: $(date)" >> $out
  lb batch-script lb-steve 'profileDiskSpace --summary' >> $out
  sleep 5
done
