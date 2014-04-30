package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

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
  private List<Status> _status = new ArrayList<Status>();

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

  public List<Status> getStatus()
  {
    return _status;
  }

  public void setStatus(List<Status> v)
  {
    _status = v;
  }

  // only to be used by internal database methods
  public void addStatus(Status status)
  {
    _status.add(status);
  }

  public boolean isSucceeded()
  {
    for(Status st : _status)
    {
      if(st.getEvent() == Status.Event.SUCCEEDED)
        return true;
    }

    return false;
  }

  public boolean isFailed()
  {
    for(Status st : _status)
    {
      if(st.getEvent() == Status.Event.FAILED)
        return true;
    }

    return false;
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