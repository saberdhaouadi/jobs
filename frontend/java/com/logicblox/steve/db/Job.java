package com.logicblox.steve.db;

import java.util.Collection;
import com.google.common.collect.ImmutableList;
import com.logicblox.steve.common.Data;

public class Job
{
  private String _id;
  private String _clientId;
  private String _impl;
  private String _output;

  private Collection<Data> _inputData;
  private Collection<Data> _outputData;

  private Status.State _state;
  private Collection<Status> _status;

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

  public void setOutputPrefix(String v)
  {
    _output = v;
  }

  public String getOutputPrefix()
  {
    return _output;
  }

  public Collection<Status> getStatus()
  {
    return _status;
  }

  public void setStatus(Collection<Status> v)
  {
    _status = ImmutableList.copyOf(v);
  }

  public boolean isSucceeded()
  {
    // TODO review if this should be done differently
    return _outputData != null;
  }

  public void setOutputData(Collection<Data> data)
  {
    _outputData = ImmutableList.copyOf(data);
  }

  public Collection<Data> getOutputData()
  {
    return _outputData;
  }

  public void setInputData(Collection<Data> data)
  {
    _inputData = ImmutableList.copyOf(data);
  }

  public Collection<Data> getInputData()
  {
    return _inputData;
  }
}