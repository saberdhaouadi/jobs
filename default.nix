{ src ? ./. }:
let
  inherit (import <config> {}) releases pkgs version buildLBConfig;
  platform = releases.platform."4.0.7";

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
      buildInputs = with platform; [ logicblox bloxweb ];
      configureFlags = "--with-commons-exec=${commons-exec} --with-protocols=${protocols}";
    };
  
}
