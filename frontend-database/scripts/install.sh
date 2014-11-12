#! /usr/bin/env bash
set -e

function trap_handler() {
  echo "Removing failed lb-steve workspace"
  lb delete lb-steve || true
}

top=$(cd $(dirname $0); pwd)

tdx="users jobimpls jobimpl_metadata jobs job_metadata job_inputs job_outputs job_status provision-config"
backup_dir="$LB_DEPLOYMENT_HOME/exports/`date +"%Y%m%d-%H%M%S"`"
latest_link=$LB_DEPLOYMENT_HOME/exports/latest

if [[ -d "$(lb filepath lb-steve)" ]]; then
  trap trap_handler ERR

  mkdir -p $backup_dir
  echo "Export data to $backup_dir"
  for t in $tdx; do
    echo " - $t"
    lb export -n -o file://$backup_dir/$t.csv http://localhost:8080/tdx/$t
  done

  lb export lb-steve $backup_dir/workspace
  rm -f $latest_link
  ln -s $backup_dir $latest_link
fi

lb create --overwrite lb-steve
lb addproject lb-steve $top/share/lb_steve_frontend_database --libpath $LB_WEBSERVER_HOME:$top/share
lb web-server load-services

if [[ -e $latest_link ]]; then
  echo "Importing data from $(readlink -f $latest_link)"
  for t in $tdx; do
    echo " - $t"
    lb web-client import -n -i file://$latest_link/$t.csv http://localhost:8080/tdx/$t
  done
fi
