#! /bin/sh -e

export NIXOS_CONFIG=$(dirname $(readlink -f $0))/nix/worker-gce-image.nix
export TIMESTAMP=$(date +%Y%m%d%H%M)

buildAndUploadFor() {
    system="$1"
    arch="$2"

    echo "building $system image..."
    nix-build '<nixpkgs/nixos>' \
        -A config.system.build.googleComputeImage --argstr system "$system" -o gce --option extra-binary-caches s3://logicblox-cache -I lbdevops=$HOME/src/lbdevops -I config=$HOME/src/config -I nixpkgs-unstable=channel:nixos-unstable
}

buildAndUploadFor x86_64-linux x86_64
