package com.logicblox.steve.db;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.common.util.concurrent.ListenableFuture;
import com.logicblox.steve.common.Data;
import com.logicblox.steve.common.Status;

/**
 * Interface for storing data about jobs. The implementations also
 * need to include necessary verifications. The methods are on purpose
 * coarse grained to give the implementations freedom of using single
 * vs many transactions to check requests. This is also the reason why
 * most arguments are primitive types, and not classes like Account or
 * User.
 */
public interface Database {

  /**
   * Get information about the user with this id.
   *
   * @param userId
   * @return
   */
  public ListenableFuture<User> getUser(String userId);


  public ListenableFuture<Account> getAccount(String userId);

  /**
   * Initial creation of a job in the database.
   * <p/>
   * <p>This method will generate a unique job id for the new job and will attempt to store the job
   * in the database. If there exists already a job with the client id, the job will be reused, so
   * the previously existing job id will be reused and returned. Otherwise, the newly generated job
   * id will be returned.
   * </p>
   * <p>
   * See {@link Job}'s attributes for a description of the parameters.
   * </p>
   *
   * @param userId
   * @param clientId
   * @param jobImplId
   * @param inputs
   * @param output
   * @param output_encryption_key
   * @param metadata
   * @return the generated id for this job (which could be reused from a previous job with this
   * clientId).
   * @see Job
   */
  public ListenableFuture<String> createJob(
          String userId,
          String clientId,
          String jobImplId,
          Collection<Data> inputs,
          String output,
          String output_encryption_key,
          Map<String, String> metadata);


  /**
   * Add this status event to the job with this id.
   *
   * @param jobId
   * @param status
   * @return the jobId passed as parameter, if the operation succeeded.
   */
  public ListenableFuture<String> addStatus(String jobId, Status status);

  /**
   * Add results to the job with this id.
   *
   * @param jobId
   * @param output
   * @return the jobId passed as parameter, if the operation succeeded.
   */
  public ListenableFuture<String> setResult(String jobId, List<Data> output);

  /**
   * Return Job with this id.
   *
   * @param jobId
   * @return
   */
  public ListenableFuture<Job> getJob(String jobId);

  /**
   * Store a job implementation in the database.
   * <p/>
   * <p>Note that job implementations are stored by id within an account. Therefore, it may be that
   * the account to which this user belongs already has a job implementation with this id. In this
   * case, the job implementation is updated with these new values.
   * </p>
   *
   * @param userId
   * @param implId
   * @param file
   * @param metadata
   * @return the implId passed as parameter, if the operation succeeded.
   */
  public ListenableFuture<String> setJobImpl(
          String userId,
          String implId,
          Data file,
          Map<String, String> metadata);

  /**
   * Get information on a job implementation.
   *
   * @param userId the user requesting the information.
   * @param id     the id of the jobimpl being requested.
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

  /**
   * Allow the database implementation to cleanup resources.
   */
  public void shutdown();
}
