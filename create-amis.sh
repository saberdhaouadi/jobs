#!/usr/bin/env nix-shell
#! nix-shell -i bash -p qemu ec2_ami_tools jq ec2_api_tools awscli

# To start with do: nix-shell -p awscli --run "aws configure"

set -eo pipefail
set -x

# parse args
while [[ $# -gt 0 ]]; do
    op="$1"
    case $op in
        -b|--bucket)
	    bucket="$2"
	    shift 2
	    ;;
	    -i|--build-id)
	    build="$2"
	    shift 2
	    ;;
            -f|--file)
            outputFile=$2
            shift 2
            ;;
            *)
            shift
            ;;
    esac
done

case $outputFile in
        dev)
                amisFile=nix/amis.nix
        ;;
        prod)
                amisFile=nix/prod-amis.nix
        ;;
        *)
                echo "please enter output file"
        ;;
esac

if [[ -z "$bucket" ]]; then
    bucket="steve-jobs-worker"
fi

stateDir=/tmp/ec2-image

echo "keeping state in $stateDir"
mkdir -p $stateDir/hvm

if [[ "$build" == "" ]]; then 
    curl -o build.json -H 'Content-Type: application/json' -L -s https://bob.logicblox.com/job/jobs/default/worker_image.ec2/latest
    build=$(cat build.json | jq -r ".id")
else
    curl -o build.json -H 'Content-Type: application/json' -L -s https://bob.logicblox.com/build/$build
fi

version=$(date +%Y%m%d%H%M)
arch="x86_64"

echo "downloading image..."
curl -L https://bob.logicblox.com/build/$build/download-by-type/file/img | xz -d > $stateDir/hvm/nixos.qcow2

echo "NixOS version is $version"

echo "{" > $amisFile

lbJobsDevAccountId="202226491534"
types="hvm"
stores="ebs s3"
regions="us-east-1 us-west-1 us-west-2"

for type in $types; do
    link=$stateDir/$type
    imageFile=$link/nixos.qcow2
    system=x86_64-linux
    arch=x86_64

    for store in $stores; do

        bucketDir="$version-$type-$store"

        prevAmi=
        prevRegion=

        for region in $regions; do

            name=steve-jobs-worker-$version-$arch-$type-$store
            description="steve-jobs-worker build $build $system $version ($type-$store)"

            amiFile=$stateDir/$region.$type.$store.ami-id

            if ! [ -e $amiFile ]; then

                echo "doing $name in $region..."

                if [ -n "$prevAmi" ]; then
                    ami=$(aws ec2 copy-image \
                        --region "$region" \
                        --source-region "$prevRegion" --source-image-id "$prevAmi" \
                        --name "$name" --description "$description" | jq -r '.ImageId')
                    if [ "$ami" = null ]; then break; fi
                else

                    if [ $store = s3 ]; then

                        # Bundle the image.
                        imageDir=$stateDir/$type-bundled

                        # Convert the image to raw format.
                        rawFile=$stateDir/$type.raw
                        if ! [ -e $rawFile ]; then
                            qemu-img convert -f qcow2 -O raw $imageFile $rawFile.tmp
                            mv $rawFile.tmp $rawFile
                        fi

                        if ! [ -d $imageDir ]; then
                            rm -rf $imageDir.tmp
                            mkdir -p $imageDir.tmp
                            ec2-bundle-image \
                                -d $imageDir.tmp \
                                -i $rawFile --arch $arch \
                                --user "$AWS_ACCOUNT" -c "$EC2_CERT" -k "$EC2_PRIVATE_KEY"
                            mv $imageDir.tmp $imageDir
                        fi

                        # Upload the bundle to S3.
                        if ! [ -e $imageDir/uploaded ]; then
                            echo "uploading bundle to S3..."
                            ec2-upload-bundle \
                                -m $imageDir/$type.raw.manifest.xml \
                                -b "$bucket/$bucketDir" \
                                -a "$AWS_ACCESS_KEY_ID" -s "$AWS_SECRET_ACCESS_KEY" \
                                --location US
                            touch $imageDir/uploaded
                        fi

                        extraFlags="--image-location $bucket/$bucketDir/$type.raw.manifest.xml"

                    else

                        # Convert the image to vhd format so we don't have
                        # to upload a huge raw image.
                        vhdFile=$stateDir/$type.vhd
                        if ! [ -e $vhdFile ]; then
                            qemu-img convert -f qcow2 -O vpc $imageFile $vhdFile.tmp
                            mv $vhdFile.tmp $vhdFile
                        fi
                        
                        # upload VHD file to S3
                        vhdS3Object=s3://$bucket/vhd/$version/$type.vhd
                        vhdObjectKey=vhd/$version/$type.vhd
                        aws s3 cp $vhdFile s3://$bucket/$vhdObjectKey

                        vhdFileLogicalBytes="$(qemu-img info "$vhdFile" | grep ^virtual\ size: | cut -f 2 -d \(  | cut -f 1 -d \ )"
                        vhdFileLogicalGigaBytes=$(((vhdFileLogicalBytes-1)/1024/1024/1024+1)) # Round to the next GB

                        echo "Disk size is $vhdFileLogicalBytes bytes. Will be registered as $vhdFileLogicalGigaBytes GB."

                        taskId=$(cat $stateDir/$region.$type.task-id 2> /dev/null || true)
                        snapId=$(cat $stateDir/$region.$type.snap-id 2> /dev/null || true)

                        echo "importing snapshot from VHD file..."
                        diskDescription="steve-jobs-worker VHD Disk - $type.$store - $version"
                        taskId=$(aws ec2 import-snapshot \
                                --region "$region" \
                                --disk-container "Description="''"$diskDescription"''",Format=vhd"''",UserBucket={S3Bucket=$bucket,S3Key=$vhdObjectKey}" | jq -r ".ImportTaskId" )

                        echo -n "$taskId" > $stateDir/$region.$type.task-id

                        while true; do
                            importTaskDesc=$(aws ec2 describe-import-snapshot-tasks --import-task-ids $taskId --region $region)
                            taskStatus=$(echo $importTaskDesc | jq -r ".ImportSnapshotTasks[0].SnapshotTaskDetail.Status")
                            if [ "$taskStatus" == "completed" ]; then break; fi
                            sleep 30
                        done

                        # get snapshot ID
                        snapId=$(echo $importTaskDesc | jq -r ".ImportSnapshotTasks[0].SnapshotTaskDetail.SnapshotId")

                        blockDeviceMappings="DeviceName=/dev/sda1,Ebs={SnapshotId=$snapId,VolumeSize=$vhdFileLogicalGigaBytes,DeleteOnTermination=true,VolumeType=gp2}"
                        extraFlags=""

                        if [ $type = pv ]; then
                            extraFlags+=" --root-device-name /dev/sda1"
                        else
                            extraFlags+=" --root-device-name /dev/sda1"
                            extraFlags+=" --sriov-net-support simple"
                            extraFlags+=" --ena-support"
                        fi

                        blockDeviceMappings+=" DeviceName=/dev/sdb,VirtualName=ephemeral0"
                        blockDeviceMappings+=" DeviceName=/dev/sdc,VirtualName=ephemeral1"
                        blockDeviceMappings+=" DeviceName=/dev/sdd,VirtualName=ephemeral2"
                        blockDeviceMappings+=" DeviceName=/dev/sde,VirtualName=ephemeral3"
                    fi

                    if [ $type = hvm ]; then
                        extraFlags+=" --sriov-net-support simple"
                        extraFlags+=" --ena-support"
                    fi

                    # Register the AMI.
                    if [ $type = pv ]; then
                        kernel=$(aws ec2 describe-images --owner amazon --filters "Name=name,Values=pv-grub-hd0_1.04-$arch.gz" | jq -r .Images[0].ImageId)
                        if [ "$kernel" = null ]; then break; fi
                        echo "using PV-GRUB kernel $kernel"
                        extraFlags+=" --virtualization-type paravirtual --kernel $kernel"
                    else
                        extraFlags+=" --virtualization-type hvm"
                    fi

                    ami=$(aws ec2 register-image \
                        --name "$name" \
                        --description "$description" \
                        --region "$region" \
                        --architecture "$arch" \
                        --block-device-mappings $blockDeviceMappings \
                        $extraFlags | jq -r .ImageId)
                    if [ "$ami" = null ]; then break; fi
                fi

                echo -n "$ami" > $amiFile
                echo "created AMI $ami of type '$type' in $region..."

            else
                ami=$(cat $amiFile)
            fi

            echo "region = $region, type = $type, store = $store, ami = $ami"

            if [ -z "$prevAmi" ]; then
                prevAmi="$ami"
                prevRegion="$region"
            fi
        done

    done

done

for type in $types; do
    link=$stateDir/$type
    system=x86_64-linux
    arch=x86_64

    for store in $stores; do

        for region in $regions; do

            name=steve-jobs-worker-$version-$arch-$type-$store
            amiFile=$stateDir/$region.$type.$store.ami-id
            ami=$(cat $amiFile)

            echo "region = $region, type = $type, store = $store, ami = $ami"

            echo -n "waiting for AMI..."
            while true; do
                status=$(aws ec2 describe-images --image-ids "$ami" --region "$region" | jq -r .Images[0].State)
                if [ "$status" = available ]; then break; fi
                sleep 10
                echo -n '.'
            done
            echo

            aws ec2 modify-image-attribute \
                --image-id "$ami" --region "$region" --launch-permission "Add=[{UserId=${lbJobsDevAccountId}}]"

            echo "  $region.$store = \"$ami\";" >> $amisFile 
        done

    done

done
echo "}" >> $amisFile
