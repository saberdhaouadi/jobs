package com.logicblox.aws;

import com.amazonaws.auth.AWSCredentialsProvider;
import com.amazonaws.auth.AWSCredentialsProviderChain;
import com.amazonaws.auth.BasicAWSCredentials;
import com.amazonaws.auth.EnvironmentVariableCredentialsProvider;
import com.amazonaws.auth.InstanceProfileCredentialsProvider;
import com.amazonaws.internal.StaticCredentialsProvider;

import com.logicblox.bloxweb.config.ConfigMap;

public class CredentialsConfig {
  private ConfigMap _config;

  public CredentialsConfig(ConfigMap config) {
    _config = config;
  }

  @Override
  public String toString() {
    if (_config != null) {
      if (_config.contains("access_key") && _config.contains("secret_key")) {
        return "access_key = " + _config.getStringError("access_key") +
                " secret_key = " + _config.getStringError("secret_key");
      } else if (_config.contains("iam_role")) {
        return "iam_role";
      } else if (_config.contains("env_credentials")) {
        return "environment";
      }
    }

    return "automatic";
  }

  public AWSCredentialsProvider getAWSCredentialsProvider() {
    if (_config != null) {
      if (_config.contains("access_key") && _config.contains("secret_key")) {
        String accessKey = _config.getStringError("access_key");
        String secretKey = _config.getStringError("secret_key");
        return new StaticCredentialsProvider(new BasicAWSCredentials(accessKey, secretKey));
      } else if (_config.contains("iam_role")) {
        return new InstanceProfileCredentialsProvider();
      } else if (_config.contains("env_credentials")) {
        return new EnvironmentVariableCredentialsProvider();
      }
    }

    return new AWSCredentialsProviderChain(
            new EnvironmentVariableCredentialsProvider(),
            new InstanceProfileCredentialsProvider());
  }
}