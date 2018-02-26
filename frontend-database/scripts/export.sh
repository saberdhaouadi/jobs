#! /usr/bin/env bash
set -e
set -x

function record_span()
{
  local id="$1"
  shift

  local t1="$(date +%s.%N)"
  "$@"
  local t2="$(date +%s.%N)"

  if ! type -P bc &> /dev/null; then
    return
  fi

  local t3="$(echo "$t2 - $t1" | bc)"

  echo "${id},${t1},${t2},${t3}" >> load-results.csv
}

top=$(cd $(dirname $0); pwd)

tdx="users jobimpls jobimpl_metadata jobs job_metadata job_inputs job_outputs job_status provision-config platform_versions"
backup_dir="$LB_DEPLOYMENT_HOME/exports/`date +"%Y%m%d-%H%M%S"`"
latest_link=$LB_DEPLOYMENT_HOME/exports/latest

if [[ -d "$(lb filepath lb-steve)" ]]; then
  mkdir -p $backup_dir
  echo "Export data to $backup_dir"
  for t in $tdx; do
    echo " - $t"
    record_span "export-$t" lb web-client export --timeout 3600 -n -o file://$backup_dir/$t.csv http://localhost:8080/tdx/$t
  done
  rm -f $latest_link
  ln -s $backup_dir $latest_link
fi
