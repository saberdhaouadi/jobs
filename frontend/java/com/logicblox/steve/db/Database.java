package com.logicblox.steve.db;

import java.security.PublicKey;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.util.concurrent.ListenableFuture;

import com.logicblox.s3lib.S3File;
import com.logicblox.steve.common.Data;

/**
 * Interface for storing data about jobs. The implementations also
 * need to include necessary verifications. The methods are on purpose
 * coarse grained to give the implementations freedom of using single
 * vs many transactions to check requests. This is also the reason why
 * most arguments are primivite types, and not classes like Account or
 * User.
 */
public interface Database
{
  public User getUser(String userid);
  public Account getAccount(String userid);

  /**
   * Initial creation of a job in the database.
   */
  public ListenableFuture<Job> createJob(
    String userid,
    String clientid,
    String jobImpl,
    Collection<Data> inputs,
    String output);

  /**
   * Store a job a implementation.
   */
  public ListenableFuture<JobImpl> setJobImpl(String userid, String id, Data file, Map<String, String> tags);

  /**
   * Get information on a job implementation.
   */
  public ListenableFuture<JobImpl> getJobImpl(String userid, String id);

  /**
   * Get all job implementations available to a user.
   */
  public ListenableFuture<Iterable<JobImpl>> getJobImpl(String userid);

  /**
   * Returns Job with the state field populated, and the full status
   * history if detail is true.
   */
  public ListenableFuture<Job> getState(String jobId, boolean detail);

  public ListenableFuture<Job> getResult(String jobId);

  public ListenableFuture<Job> addStatus(String jobId, Status status);

  public ListenableFuture<Job> setResult(String jobId, List<Data> output);
}
