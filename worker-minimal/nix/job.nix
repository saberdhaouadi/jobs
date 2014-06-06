{ pkgs ? import <nixpkgs> {}
}:

  pkgs.stdenv.mkDerivation rec {
    name = "job-${toString builtins.currentTime}";
    buildInputs = [
      pkgs.pythonFull
      pkgs.socat
    ];

    buildCommand = ''
      tar --strip-components=1 -xf /tmp/job/job.tar.gz

      echo ""
      echo "running job"
      if [[ -f ./run ]]; then
        bash run /tmp/job/in /tmp/job/out
      else
        echo "ERROR: 'run' script not found in job!"
      fi

      rm -rf $out
      mkdir -p $out
    '';
    failureHook = exitHook;
    exitHook = ''
      chmod -R 777 . /tmp/job/out/*
    '';
  }
