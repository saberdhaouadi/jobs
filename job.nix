{ stdenv
, fetchurl
, src
, logicblox
, lb_web
, s3lib
, jdk
, unzip
, builder_config
, makeWrapper
, runCommand
, python
}:
let
  version = builder_config.version;

  buildjar = {name, url, sha256} :
    stdenv.mkDerivation rec {
      inherit name;
      src = fetchurl { inherit url sha256; };
      buildCommand = ''
        ensureDir $out/lib/java
        cp $src $out/lib/java/$name.jar
      '';
    };

  commons-exec =
    buildjar {
      name = "commons-exec";
      url = http://repo1.maven.org/maven2/org/apache/commons/commons-exec/1.2/commons-exec-1.2.jar;
      sha256 = "1f0b1cg17k79cjij6fpichrh9jzrn0q3dxf8z2a8af23id1w49pk";
    };

  commons-cli =
    buildjar {
      name = "commons-cli";
      url = http://repo1.maven.org/maven2/commons-cli/commons-cli/1.2/commons-cli-1.2.jar;
      sha256 = "1nar28vxmzsjiw12phv77q8qr6jjnbsx9kvwidb9nd3djm8qkkg7";
    };

  aws-java-sdk =
    stdenv.mkDerivation rec {
      name = "aws-java-sdk-1.7.1";
      src = fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.7.1.zip;
        sha256 = "afc1a93635b5e77fb2f1fac4025a3941300843dce7fc5af4f2a99ff9bf4af05b";
      };
      buildInputs = [unzip];
      buildCommand = ''
        unzip $src

        ensureDir $out/lib/java

        for f in $(find ${name} -name '*.jar'); do
          cp $f $out/lib/java
        done
      '';
    };

in
rec {
  frontend =
     builder_config.buildLBConfig {
      name = "jobs-frontend-${version src}";
      src = "${src}/frontend";
      buildInputs = [ logicblox lb_web makeWrapper ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-s3lib=${s3lib}"
        "--with-aws=${aws-java-sdk}"
        "--with-commons-cli=${commons-cli}"
      ];
    };

  client.build =
    builder_config.buildLBConfig {
      name = "lb-steve-client-${version src}";
      src = "${src}/client";
      buildInputs = [ logicblox lb_web ];
      enableLBservices = false;
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-aws=${aws-java-sdk}"
        "--with-s3lib=${s3lib}"
      ];
    };

  client.binary_tarball =
    builder_config.release_helper {
      name = "lb-steve-client";
      inherit (client) build;
    };

  protocols =
    builder_config.buildLBConfig {
      name = "jobs-protocols-${version src}";
      src = "${src}/protocols";
      buildInputs = [ logicblox lb_web ];
      enableLBservices = false;
      configureFlags = [
        "--with-aws=${aws-java-sdk}"
        "--with-s3lib=${s3lib}"
      ];
    };

  worker =
    builder_config.buildLBConfig {
      name = "jobs-worker-${version src}";
      src = "${src}/worker";
      buildInputs = [ logicblox lb_web makeWrapper ];
      enableLBservices = false;
      configureFlags = [
        "--with-commons-exec=${commons-exec}"
        "--with-commons-cli=${commons-cli}"
        "--with-protocols=${protocols}"
        "--with-aws=${aws-java-sdk}"
        "--with-s3lib=${s3lib}"
      ];
      postInstall = ''
        for b in lb-steve-worker lb-steve-provisioner; do 
          wrapProgram "$out/bin/$b" --prefix PATH : "${python}/bin:${jdk}/bin"
        done
      '';
    };

  worker_image.ec2 =
    let
      image = (import <nixpkgs/nixos> { system = "x86_64-linux"; configuration = ./nix/worker-ec2-image.nix; }).config.system.build.amazonImage;
    in 
      runCommand "worker-ec2-image-${version src}" { preferLocalBuild = true; } ''
        mkdir -p $out/nix-support
        xz -z -c ${image}/nixos.img  > $out/worker-${version src}.img.xz
        echo "file img $out/worker-${version src}.img.xz" > $out/nix-support/hydra-build-products
      '';

  database =
    builder_config.genericAppJobset {
      build = builder_config.buildLBConfig {
        name = "jobs-database-${version src}";
        src = "${src}/frontend-database";
        buildInputs = [ logicblox lb_web ];
      };
    };

}

