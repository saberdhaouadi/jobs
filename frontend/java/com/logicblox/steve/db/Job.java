package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.logicblox.steve.common.Data;

public class Job
{
  public String id;
  public String clientId;
  public String outputPrefix;

  public JobImpl impl;

  public Map<String, String> metadata = new HashMap<String, String>();

  private Collection<Data> _inputData;
  private Collection<Data> _outputData;

  private Status.State _state;
  private List<Status> _status = new ArrayList<Status>();

  public synchronized List<Status> getStatus()
  {
    // Note: status is mutable, so we protect it here against
    // concurrent access issues.
    return ImmutableList.copyOf(_status);
  }

  public synchronized void setStatus(List<Status> v)
  {
    _status = v;
  }

  // Note: this method is only to be used by internal database methods
  public synchronized void addStatus(Status status)
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
