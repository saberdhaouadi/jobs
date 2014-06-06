#! /bin/sh

set -e
set -u

build()
{
  pushd $1
  rm -rf build
  lb config
  make
  make install
  popd
}

build protocols
build worker
build frontend
build client
