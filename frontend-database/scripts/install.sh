#! /usr/bin/env bash
set -e

result_time()
{
  local id="$1"
  shift
  if type -P time; then
    env time -f "#RESULT# $id %e seconds" $@
  else
    $@
  fi
}

db_created=

function trap_handler() {
  if [[ -n "$db_created" ]]; then
    echo "Removing failed lb-steve workspace"
    lb delete lb-steve || true
  fi
}

top=$(cd $(dirname $0); pwd)

tdx="users jobimpls jobimpl_metadata jobs job_metadata job_inputs job_outputs job_status provision-config platform_versions"
backup_dir="$LB_DEPLOYMENT_HOME/exports/`date +"%Y%m%d-%H%M%S"`"
latest_link=$LB_DEPLOYMENT_HOME/exports/latest

if [[ -d "$(lb filepath lb-steve)" ]]; then
  trap trap_handler ERR

  mkdir -p $backup_dir
  echo "Export data to $backup_dir"
  for t in $tdx; do
    echo " - $t"
    result_time "lb-jobs-load export-$t" lb web-client export --timeout 3600 -n -o file://$backup_dir/$t.csv http://localhost:8080/tdx/$t
  done

  result_time "lb-jobs-load export-workspace" lb export-workspace lb-steve $backup_dir/workspace
  rm -f $latest_link
  ln -s $backup_dir $latest_link
fi

db_created=1
lb create --overwrite lb-steve

proj=$top/share/lb_steve_frontend_database
if [[ ! -d $proj ]]; then
  proj=$top/share/lb-steve-frontend-database/lb_steve_frontend_database
fi
lb addproject lb-steve $proj --libpath $LB_WEBSERVER_HOME:$top/share
lb web-server load-services

if [[ -e $latest_link ]]; then
  echo "Importing data from $(readlink -f $latest_link)"
  for t in $tdx; do
    if [[ -f $latest_link/$t.csv ]]; then
      echo " - $t"
      result_time "lb-jobs-load import-$t" lb web-client import --timeout 3600 -n -i file://$latest_link/$t.csv http://localhost:8080/tdx/$t
    fi
  done
fi
