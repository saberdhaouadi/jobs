#! /bin/sh -e
set -x
build=$1
if [[ "$build" == "" ]]; then 
curl -o build.json -H 'Content-Type: application/json' -L -s https://bob.logicblox.com/job/jobs/default/worker_image.ec2/latest
else
curl -o build.json -H 'Content-Type: application/json' -L -s https://bob.logicblox.com/build/$build
fi

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

ami=$(cat image.json | json ImageId)
sed -i "s|\"ami-.*\"|\"$(cat image.json | json ImageId)\"|" worker/java/com/logicblox/steve/provision/Main.java

echo "{" > nix/amis.nix
echo "  us-east-1 = \"$ami\"" >> nix/amis.nix
for region in us-west-1 us-west-2; do
  aws ec2 copy-image --region $region --source-region us-east-1 --source-image-id $ami --name "$name $TIMESTAMP build $build" | tee $region.json
  echo "  $region = \"$(cat $region.json | json ImageId)\"" >> nix/amis.nix
done

echo "}" >> nix/amis.nix
