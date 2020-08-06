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

  google-api-services-compute =
    buildjar {
      name = "google-api-services-compute";
      url = http://central.maven.org/maven2/com/google/apis/google-api-services-compute/v1-rev188-1.23.0/google-api-services-compute-v1-rev188-1.23.0.jar;
      sha256 = "1ldwhkhhw4ds72zc0wr9pl08kf8sjr7j9sbp4xzk9d7722fw96cm";

    };

  aws-java-sdk =
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

  google-java-sdk =
    with pkgs; stdenv.mkDerivation rec {
      name = "google-java-sdk-1.23.0";
      src = fetchurl {
        url = http://central.maven.org/maven2/com/google/api-client/google-api-client-assembly/1.23.0/google-api-client-assembly-1.23.0-1.23.0.zip ;
        sha256 = "07x5jgbp0cini29avz37ppzrlvwqfdb0d8g4il5xcy38ivsmms53";
      };
      buildInputs = [ pkgs.unzip google-api-services-compute ];
      buildCommand = ''
        mkdir  $out/
        unzip  $src -d $out
        cp ${google-api-services-compute}/lib/java/google-api-services-compute.jar $out/
      '';
    };



in
rec {
  inherit aws-java-sdk google-java-sdk;


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
