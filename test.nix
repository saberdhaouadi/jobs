{ walgreens_ml_src ? <walgreens_ml_src>
, walgreens_dm_src ? <walgreens_dm_src>
}:
let
  builder_config = import <config> {};
  pkgs = builder_config.pkgs;
  impl = platform_release: (import walgreens_ml_src { inherit walgreens_ml_src walgreens_dm_src platform_release; }).build;

  data = builder_config.fetchs3 {
    url = "s3://logicblox-private/data/lb-jobs-in.tgz";
    sha256 = "03d6hsd8d1s582xa7a3g7hrfclnk5gl2yjmspjbxfpvvq5rdbfm9";
  };

  runJob = platform:
    builder_config.buildLB {
      name = "run-job";
      buildInputs = [ (builder_config.getLB platform) pkgs.jq ];
      buildCommand = ''
        tar -xf ${data}
        echo '${metadata}' > in/metadata.json
        mkdir out impl
        pushd impl
        tar --strip-components=1 -xf ${impl platform}/implementations/wag_ML_features_extraction.tgz
        ./run ../in ../out
        popd
        ls -lR out
        mkdir $out
      '';
      requiredSystemFeatures = [ "perf" ];
    };
    
  metadata = builtins.toJSON {
    job_tag = "job00002_066";
  };
in
{
  "4_4_3" = runJob "4.4.3";
  "4_4_3_9" = runJob "4.4.3.9";
}
