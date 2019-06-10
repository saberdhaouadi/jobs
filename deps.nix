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
    /*with pkgs; stdenv.mkDerivation rec {
      name = "aws-java-sdk-1.11.102";
      src = fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.11.102.zip;
        sha256 = "c06a529b86c08d73b840adc6fe103d49d7ff3eea011977267f0f350a333c2fb3";
      };*/

   /* with pkgs; stdenv.mkDerivation rec {
    name = "aws-java-sdk-1.11.476";
      src = fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.11.476.zip;
        sha256 = "7d950aa9439134c61e0204b5debb15aadfd1ce18f4d71c0071f9100c6cf63c81";
      };*/
    /*with pkgs; stdenv.mkDerivation rec {
      name = "aws-java-sdk-1.11.536";
      src = fetchurl {
        url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.11.536.zip;
        sha256 = "67547b5595bb0d8a419418419303ccd7db97df83b0087e6c1355e1522cc5b424";
      };*/
    with pkgs; stdenv.mkDerivation rec {
        name = "aws-java-sdk-1.11.560";
        src = fetchurl {
          url = http://sdk-for-java.amazonwebservices.com/aws-java-sdk-1.11.560.zip;
          sha256 = "6ce540cbeefc4bd411dd4aaa936f4a3f55f68f7584206ddf8dcc0a98d6fbc2a8";
        };



      buildInputs = [ pkgs.unzip ];
      buildCommand = ''
        # o option is necessary because the archive contains two
        # documentation files that have identical case-insensitive
        # names.
        unzip -o $src
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
