package com.logicblox.steve.client;

import com.logicblox.steve.protocol.Frontend;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

public class SteveClientException extends Exception {
  /**
   * HTTP status of the response.
   */
  public int status = -1;

  /**
   * Service URL
   */
  public String service = null;

  /**
   * Error message
   */
  public String error = null;

  /**
   * Error code
   */
  public String error_code = null;

  public SteveClientException(String service, String error, String error_code) {
    this.service = service;
    this.error = error;
    this.error_code = error_code;
  }

  public SteveClientException(String service, int status, Frontend.Response response) {
    this.status = status;
    this.service = service;

    if (response != null) {
      if (response.hasError())
        this.error = response.getError();
      if (response.hasErrorCode())
        this.error_code = response.getErrorCode();
    }
  }

  @Override
  public String toString() {
    return toJSON();
  }

  public String toJSON() {
    JsonObject o = new JsonObject();

    if (this.error != null)
      o.addProperty("error", this.error);

    if (this.error_code != null)
      o.addProperty("error_code", this.error_code);

    if (this.service != null)
      o.addProperty("service", this.service);

    if (this.status != -1)
      o.addProperty("http_status", this.status);

    return new Gson().toJson(o);
  }
}
