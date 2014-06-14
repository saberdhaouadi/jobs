package com.logicblox.steve.client;

import com.logicblox.steve.protocol.Frontend;

public class SteveClientException extends Exception
{
  /**
   * HTTP status of the response.
   */
  public final int status;

  /**
   * Response message
   */
  public final Frontend.Response response;

  /**
   * Service URL
   */
  public final String service;

  public SteveClientException(String service, int status, Frontend.Response response)
  {
    this.status = status;
    this.service = service;
    this.response = response;
  }

  @Override
  public String toString() 
  {
    StringBuilder builder = new StringBuilder();

    if(this.response != null)
    {
      if(this.response.hasError())
        builder.append("error: " + response.getError());
      
      if(this.response.hasErrorCode())
        builder.append(", code " + this.response.getErrorCode());
    }

    if(this.service != null)
      builder.append(", service '" + this.service + "'");
    
    if(this.status != -1)
      builder.append(", http-status " + this.status);

    return builder.toString();
  }
}
