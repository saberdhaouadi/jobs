package com.logicblox.steve.db;

public class Job
{
  public String _id;
  public String _clientId;
  public String _impl;
  public String _output;

  public void setId(String v)
  {
    _id = v;
  }

  public String getId()
  {
    return _id;
  }

  public void setImpl(String v)
  {
    _impl = v;
  }

  public String getImpl()
  {
    return _impl;
  }

  public void setClientId(String v)
  {
    _clientId = v;
  }

  public String getClientId()
  {
    return _clientId;
  }

  public void setOutput(String v)
  {
    _output = v;
  }

  public String getOutput()
  {
    return _output;
  }
 
}