{ walgreens_ml_src ? <walgreens_ml_src>
, walgreens_dm_src ? <walgreens_dm_src>
}:
let
  builder_config = import <config> {};
  impl = platform_release: (import walgreens_ml_src { inherit walgreens_ml_src walgreens_dm_src platform_release; }).build;

  data = builder_config.fetchs3 {
    url = "s3://logicblox-private/data/lb-jobs-in.tgz";
    sha256 = "03d6hsd8d1s582xa7a3g7hrfclnk5gl2yjmspjbxfpvvq5rdbfm9";
  };

  runJob = platform:
    builder_config.buildLB {
      name = "run-job";
      buildInputs = [ (builder_config.getLB platform) ];
      buildCommand = ''
        tar -xf ${data}
        mkdir out impl
        pushd impl
        tar --strip-components=1 -xf ${impl}/implementations/wag_ML_features_extraction.tgz
        ./run ../in ../out
        popd
        ls -lR out
        mkdir $out
      '';
      requiredSystemFeatures = [ "perf" ];
    };
    
in
{
  prod = runJob "4.3.11.4";
  ort = runJob "4.4.3";
}
