with builtins; 
let
  pkgs = import <nixpkgs> {};
  versions = filter (a: match "^[0-9]+([.][0-9]+)+$" a != null) (attrNames (import (getEnv "PLATFORM_RELEASES") { lb = null; }));
in
  pkgs.runCommand "platform_versions.csv" {} (''
     echo 'VERSION' > $out
     echo '"3.10.15"' >> $out
   '' + (pkgs.lib.concatMapStrings (v: ''
     echo '"${v}"' >> $out
   '') versions))

