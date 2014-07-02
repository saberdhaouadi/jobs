#! /bin/sh -e

export NIXOS_CONFIG=$(dirname $(readlink -f $0))/nix/worker-ec2-image.nix
export TIMESTAMP=$(date +%Y%m%d%H%M)

    system="x86_64-linux"
    arch="x86_64"

    echo "downloading $system image..."
    rm -f /tmp/nixos.img.*
    curl -L https://bob.logicblox.com/job/jobs/default/worker_image.ec2/latest/download-by-type/file/img | xz -d > /tmp/nixos.img

    ec2-bundle-image -i /tmp/nixos.img --user "$AWS_ACCOUNT" --arch "$arch" \
        -c "$EC2_CERT" -k "$EC2_PRIVATE_KEY"

    for region in us-east-1; do
        echo "uploading $system image for $region..."

        name=steve-jobs-worker-$arch-s3
        bucket="steve-jobs-worker"

        if [ "$region" = eu-west-1 ]; then s3location=EU;
        elif [ "$region" = us-east-1 ]; then s3location=US;
        else s3location="$region"
        fi

        ec2-upload-bundle --retry -b "$bucket/$TIMESTAMP" -m /tmp/nixos.img.manifest.xml \
            -a "$EC2_ACCESS_KEY" -s "$EC2_SECRET_KEY" --location "$s3location" \
            --url http://s3.amazonaws.com

        aws ec2 register-image --image-location $bucket/$TIMESTAMP/nixos.img.manifest.xml --name "$name $TIMESTAMP" --region us-east-1 --virtualization-type hvm
    done

