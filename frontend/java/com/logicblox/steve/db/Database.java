package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.util.concurrent.ListenableFuture;

import com.logicblox.steve.common.Data;

/**
 * Interface for storing data about jobs. The implementations also
 * need to include necessary verifications. The methods are on purpose
 * coarse grained to give the implementations freedom of using single
 * vs many transactions to check requests. This is also the reason why
 * most arguments are primitive types, and not classes like Account or
 * User.
 */
public interface Database
{
  
  /**
   * Get information about the user with this id.
   * 
   * @param userId
   * @return
   */
  public User getUser(String userId);
  
  
  public Account getAccount(String userId);

  /**
   * Initial creation of a job in the database.
   */
  public ListenableFuture<Job> createJob(
    String userId,
    String clientId,
    String jobImpl,
    Collection<Data> inputs,
    String output,
    Map<String, String> metadata);


  /**
   * Returns Job with the state field populated, and the full status
   * history if detail is true.
   */
  public ListenableFuture<Job> getState(String jobId, boolean detail);

  public ListenableFuture<Job> addStatus(String jobId, Status status);

  public ListenableFuture<Job> setResult(String jobId, List<Data> output);

  public ListenableFuture<Job> getResult(String jobId);

  /**
   * Store a job a implementation.
   */
  public ListenableFuture<JobImpl> setJobImpl(
    String userId,
    String implId,
    Data file,
    Map<String, String> metadata);

  /**
   * Get information on a job implementation.
   *
   * @param userId the user requesting the information.
   * @param id the id of the jobimpl being requested.
   * @return information about the jobImpl if the id exists and the user has access to it (i.e., if
   * the impl belongs to some user in the same account as the user requesting the information). 
   * Otherwise, the future will be failed.
   */
  public ListenableFuture<JobImpl> getJobImpl(String userId, String id);

  /**
   * Get all job implementations available to a user.
   * 
   * @param userId the user requesting the information.
   */
  public ListenableFuture<Iterable<JobImpl>> getJobImpl(String userId);

}
