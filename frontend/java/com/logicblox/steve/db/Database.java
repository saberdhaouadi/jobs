package com.logicblox.steve.db;

import java.security.PublicKey;

import com.google.common.util.concurrent.ListenableFuture;

public interface Database
{
  public User getUser(String userid);
  public Account getAccount(String userid);

  /**
   * Initial creation of a job in the database.
   */
  public ListenableFuture<Job> createJob(
    String userid, String clientid, String jobImpl, String output);

  /**
   * Returns Job with the state field populated, and the full status
   * history if detail is true.
   */
  public ListenableFuture<Job> getState(String jobId, boolean detail);

  public ListenableFuture<Job> getResult(String jobId);
}
