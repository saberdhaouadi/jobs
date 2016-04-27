#! /bin/sh -e
build=0
curl -o build.json -H 'Content-Type: application/json' -L -s https://bob.logicblox.com/job/jobs/default/worker_image.ec2/latest

build=$(cat build.json | json id)
export TIMESTAMP=$(date +%Y%m%d%H%M)

arch="x86_64"

echo "downloading image..."
rm -f /tmp/nixos.img.*
curl -L https://bob.logicblox.com/build/$build/download-by-type/file/img | xz -d > /tmp/nixos.img

ec2-bundle-image -i /tmp/nixos.img --user "$AWS_ACCOUNT" --arch "$arch" \
    -c "$EC2_CERT" -k "$EC2_PRIVATE_KEY"

echo "uploading image..."

name=steve-jobs-worker-$arch-s3
bucket="steve-jobs-worker"

ec2-upload-bundle --retry -b "$bucket/$TIMESTAMP-$build" -m /tmp/nixos.img.manifest.xml \
            -a "$EC2_ACCESS_KEY" -s "$EC2_SECRET_KEY" --location US \
            --url http://s3.amazonaws.com

aws ec2 register-image --image-location $bucket/$TIMESTAMP-$build/nixos.img.manifest.xml --name "$name $TIMESTAMP build $build" --region us-east-1 --virtualization-type hvm | tee image.json

sed -i "s|\"ami-.*\"|\"$(cat image.json | json ImageId)\"|" worker/java/com/logicblox/steve/provision/Main.java


