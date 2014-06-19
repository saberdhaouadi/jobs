#! /bin/sh -e

export NIXOS_CONFIG=$(dirname $(readlink -f $0))/nix/worker-ec2-image.nix
export TIMESTAMP=$(date +%Y%m%d%H%M)

    system="x86_64-linux"
    arch="x86_64"

    echo "building $system image..."
    nix-build '<nixpkgs/nixos>' -j 4 \
        -A config.system.build.amazonImage --argstr system "$system" -o ec2-ami 

    ec2-bundle-image -i ec2-ami/nixos.img --user "$AWS_ACCOUNT" --arch "$arch" \
        -c "$EC2_CERT" -k "$EC2_PRIVATE_KEY"

    for region in us-east-1; do
        echo "uploading $system image for $region..."

        name=steve-jobs-worker-$arch-s3
        bucket="$(echo $name-$region | tr '[A-Z]_' '[a-z]-')"

        if [ "$region" = eu-west-1 ]; then s3location=EU;
        elif [ "$region" = us-east-1 ]; then s3location=US;
        else s3location="$region"
        fi

        ec2-upload-bundle --retry -b "$bucket/$TIMESTAMP" -m /tmp/nixos.img.manifest.xml \
            -a "$EC2_ACCESS_KEY" -s "$EC2_SECRET_KEY" --location "$s3location" \
            --url http://s3.amazonaws.com

        aws ec2 register-image --image-location $bucket/$TIMESTAMP/nixos.img.manifest.xml --name "$name $TIMESTAMP" --region us-east-1 --virtualization-type hvm
    done

