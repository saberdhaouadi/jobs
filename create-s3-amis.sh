#! /bin/sh -e

export NIXOS_CONFIG=$(dirname $(readlink -f $0))/nix/worker-ec2-image.nix
export TIMESTAMP=$(date +%Y%m%d%H%M)

buildAndUploadFor() {
    system="$1"
    arch="$2"

    echo "building $system image..."
    nix-build '<nixpkgs/nixos>' \
        -A config.system.build.amazonImage --argstr system "$system" -o ec2-ami

    ec2-bundle-image -i ./ec2-ami/nixos.img --user "$AWS_ACCOUNT" --arch "$arch" \
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

        kernel=$(ec2-describe-images -o amazon --filter "manifest-location=*pv-grub-hd0_1.03-$arch*" --region "$region" | cut -f 2)
        echo "using PV-GRUB kernel $kernel"

        ami=$(ec2-register "$bucket/$TIMESTAMP/nixos.img.manifest.xml" -n "$name $TIMESTAMP" -d "NixOS $system r$revision" \
            --region "$region" --kernel "$kernel" | cut -f 2)

        echo "AMI ID is $ami"

        echo $ami >> $region.s3.ami-id
    done
}

buildAndUploadFor x86_64-linux x86_64
