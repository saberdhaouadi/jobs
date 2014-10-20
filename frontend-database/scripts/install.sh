#! /usr/bin/env bash
set -e

top=$(cd $(dirname $0); pwd)
lb create --overwrite lb-steve
lb addproject lb-steve $top/share/lb_steve_frontend_database --libpath $LB_WEBSERVER_HOME:$top/share
lb web-server load-services
