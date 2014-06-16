package com.logicblox.steve.client;

import java.net.URI;
import java.util.List;

import com.google.common.util.concurrent.ListenableFuture;
import com.logicblox.bloxweb.client.ServiceClientException;
import com.logicblox.steve.protocol.Frontend;

/**
 * Client-side API for making calls to steve. This is used by the
 * lb-steve-client, but is also intended to be used in applications.
 *
 * Futures throw a SteveClientException for graceful exceptions
 * reported by the server, next to other exceptions like IOException
 * and ConnectException.
 */ 
public interface SteveClientInterface
{
  /**
   * Create a new job, returns an asynchronous job id.
   */
  public ListenableFuture<String> createJob(String jobImpl, Iterable<Frontend.File> inputs, URI outputPrefix, Iterable<Frontend.Param> metadata)
  throws ServiceClientException;

  /**
   * Get the state of the specified job id.
   */
  public ListenableFuture<Frontend.State> getState(String id)
  throws ServiceClientException;

  /**
   * Get the result of the specified job id.
   */
  public ListenableFuture<List<Frontend.File>> getResult(String id)
  throws ServiceClientException;

  /**
   * Wait for completion of a job id, with a fixed delay.
   *
   * If notify is not null, then every state response during polling is reported to the notify object.
   * Returns a state on completion of a job (succeeeded or failed)
   */
  public ListenableFuture<Frontend.State> wait(String id, long pollDelaySeconds, StateNotify notify)
  throws ServiceClientException;

  /**
   * Wait for completion of a job id, with a fixed delay. Returns the output of the job.
   *
   * If notify is not null, then every state response during polling is reported to the notify object.
   * Future throws SteveClientException if the job completed, but did not succeed.
   */
  public ListenableFuture<List<Frontend.File>> waitForJob(String id, long pollDelaySeconds, StateNotify notify)
  throws ServiceClientException;

  /**
   * Asynchronously upload a new job implementation.
   *
   * This returns a job id, which should be used to check for the
   * status of completion.
   */
  public ListenableFuture<String> addJobImpl(String jobImpl, Frontend.File archive, Iterable<Frontend.Param> metadata)
  throws ServiceClientException;

  /**
   * List job implementations
   */
  public ListenableFuture<List<Frontend.JobImplInfo>> getJobImplList()
  throws ServiceClientException;

  public interface StateNotify
  {
    public void notify(Frontend.State state);
  }
}
