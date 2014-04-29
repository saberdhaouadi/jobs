{ src ? ./. }:
let
  inherit (import <config> {}) releases pkgs version buildLBConfig;
  platform = releases.platform."4.0.8";

  buildjar = {name, url, sha256} :
    with pkgs; stdenv.mkDerivation rec {
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
    pkgs.stdenv.mkDerivation rec {
      name = "aws-java-sdk-1.7.1";
      src = pkgs.fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.7.1.zip;
        sha256 = "afc1a93635b5e77fb2f1fac4025a3941300843dce7fc5af4f2a99ff9bf4af05b";
      };
      buildInputs = [pkgs.unzip];
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
    buildLBConfig {
      name = "jobs-frontend-${version src}";
      src = ./frontend;
      buildInputs = with platform; [ logicblox bloxweb ];
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-s3lib=${platform.s3lib}"
        "--with-aws=${aws-java-sdk}"
      ];
    };

  client =
    buildLBConfig {
      name = "jobs-client-${version src}";
      src = ./client;
      buildInputs = with platform; [ logicblox bloxweb ];
      configureFlags = [
        "--with-protocols=${protocols}"
        "--with-s3lib=${platform.s3lib}"
      ];
    };

  protocols =
    buildLBConfig {
      name = "jobs-protocols-${version src}";
      src = ./protocols;
      buildInputs = with platform; [ logicblox bloxweb ];
      enableLBservices = false;
      configureFlags = [
        "--with-aws=${aws-java-sdk}"
      ];
    };

  worker =
    buildLBConfig {
      name = "jobs-worker-${version src}";
      src = ./worker;
      buildInputs = with platform; [ logicblox bloxweb pkgs.makeWrapper ];
      configureFlags = [
        "--with-commons-exec=${commons-exec}"
        "--with-commons-cli=${commons-cli}"
        "--with-protocols=${protocols}"
        "--with-aws=${aws-java-sdk}"
        "--with-s3lib=${platform.s3lib}"
      ];
      postInstall = ''
        wrapProgram "$out/bin/lb-steve-worker" --prefix PATH : "${pkgs.python}/bin:${pkgs.openjdk}/bin"
      '';
    };

  worker_image.ec2 =
    let
      image = (import <nixpkgs/nixos> { system = "x86_64-linux"; configuration = ./nix/worker-ec2-image.nix; }).config.system.build.amazonImage;
    in 
      with pkgs; runCommand "worker-ec2-image-${version src}" {} ''
        mkdir -p $out/nix-support
        ln -s ${image}/nixos.img $out/worker-${version src}.img
        echo "file img $out/worker-${version src}.img" > $out/nix-support/hydra-build-products
      '';

}

