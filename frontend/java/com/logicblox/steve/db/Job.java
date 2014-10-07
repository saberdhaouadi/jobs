package com.logicblox.steve.db;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import com.logicblox.steve.common.Data;

/**
 * A representation of the life-cycle of a job.
 * 
 * Most of this class is immutable (the data that comes when creating the job), but parts of it are
 * inherently mutable (outputs and status changes).
 */
public final class Job {
  
  public final String id;
  public final String clientId;
  public final String outputPrefix;
  public final JobImpl impl;
  public final Map<String, String> metadata;
  public final Collection<Data> inputData;

  // mutable state
  private final Collection<Data> _outputData = new HashSet<Data>();
  private final List<Status> _status = new ArrayList<Status>();

  public Job(String jobId, 
             String clientId, 
             String outputPrefix, 
             JobImpl jobImpl, 
             Map<String, String> metadata,
             Collection<Data> inputData) {
    
    this.id = jobId;
    this.clientId = clientId;
    this.outputPrefix = outputPrefix;
    this.impl = jobImpl;
    // because strings and Data are immutable, it is safe to just wrap around unmodifiables.
    this.metadata = Collections.unmodifiableMap(metadata);
    this.inputData = Collections.unmodifiableCollection(inputData);
  }  
  
  synchronized void addStatus(Status status) {
    // Note: this method is only to be used by internal database methods    
    _status.add(status);
  }
  
  synchronized void addOutputData(Collection<Data> output) {
    // Note: this method is only to be used by internal database methods    
    _outputData.addAll(output);
  }
  
  public synchronized List<Status> getStatus() {
    // status is immutable, but we need to protect the status list from changes
    return Collections.unmodifiableList(_status);
  }
  
  public synchronized Collection<Data> getOutputData() {
    // Data is immutable, but we need to protect the status list from changes
    return Collections.unmodifiableCollection(_outputData);
  }
  
  public boolean isSucceeded() {
    return hasEvent(Status.Event.SUCCEEDED);
  }

  public boolean isFailed() {
    return hasEvent(Status.Event.FAILED);
  }

  public boolean hasEvent(Status.Event event) {
    for(Status st : _status)
      if(st.getEvent() == event)
        return true;

    return false;
  }
}
