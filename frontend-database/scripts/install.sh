#! /usr/bin/env bash

lb create --overwrite lb-steve
lb addproject lb-steve $(cd $(dirname $0); pwd)/share/lb_steve_frontend_database --libpath $LB_WEBSERVER_HOME
lb web-server load-services
