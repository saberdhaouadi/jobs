package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;

import com.google.common.collect.ImmutableList;
import com.logicblox.steve.common.Data;

public class Job
{
  public String id;
  public String clientId;
  public String outputPrefix;

  public JobImpl impl;

  private Collection<Data> _inputData;
  private Collection<Data> _outputData;

  private Status.State _state;
  private List<Status> _status = new ArrayList<Status>();

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
