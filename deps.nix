{ pkgs }:

let

  buildjar = {name, url, sha256} :
    with pkgs; stdenv.mkDerivation rec {
      inherit name;
      src = fetchurl { inherit url sha256; };
      buildCommand = ''
        mkdir -p $out/lib/java
        cp $src $out/lib/java/$name.jar
      '';
    };

  aws-java-sdk =
    with pkgs; stdenv.mkDerivation rec {
      name = "aws-java-sdk-1.11.102";
      src = fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.11.102.zip;
        sha256 = "c06a529b86c08d73b840adc6fe103d49d7ff3eea011977267f0f350a333c2fb3";
      };
      buildInputs = [ pkgs.unzip ];
      buildCommand = ''
        set -x
        unzip $src
        mkdir -p $out/lib/java
        cp $name/lib/$name.jar $out/lib/java
      '';
    };



in
rec {
  inherit aws-java-sdk;

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
}
