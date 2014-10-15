#! /usr/bin/env bash

set -e
set -u

build()
{
  echo "Building $1..."
  pushd $1
  rm -rf build
  lb config
  make
  make install
  popd
  echo
}

build protocols
build worker
build frontend-database
build frontend
build client
