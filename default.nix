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

  joda-time =
    buildjar {
      name = "joda-time-2.3";
      url = http://repo1.maven.org/maven2/joda-time/joda-time/2.3/joda-time-2.3.jar;
      sha256 = "0fwq6k98qr68graj74qgryyi4rrmkffbvb49snpv7y21cq0dhbv0";
    };

in
rec {
  frontend =
    buildLBConfig {
      name = "jobs-frontend-${version src}";
      src = ./frontend;
      buildInputs = with platform; [ logicblox bloxweb ];
    };

  protocols =
    buildLBConfig {
      name = "jobs-protocols-${version src}";
      src = ./protocols;
      buildInputs = with platform; [ logicblox ];
      enableLBservices = false;
    };

  worker =
    buildLBConfig {
      name = "jobs-worker-${version src}";
      src = ./worker;
      buildInputs = with platform; [ logicblox bloxweb pkgs.makeWrapper ];
      configureFlags = "--with-commons-exec=${commons-exec} --with-protocols=${protocols} --with-joda-time=${joda-time} --with-s3lib=${platform.s3lib}";
      postInstall = ''
        wrapProgram "$out/bin/lb-steve-worker" --prefix PATH : "${pkgs.python}/bin:${pkgs.openjdk}/bin"
      '';
    };

  worker_image =
    let
      image = (import <nixpkgs/nixos> { system = "x86_64-linux"; configuration = ./nix/worker.nix; }).config.system.build.amazonImage;
    in 
      with pkgs; runCommand "worker-image-${version src}" {} ''
        mkdir $out/nix-support
        echo "file img $out/worker-${version src}.img" > $out/nix-support/hydra-build-products
      '';
}

