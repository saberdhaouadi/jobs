package com.logicblox.steve.db;

import java.util.Map;

import com.logicblox.steve.common.Data;

/**
 * Immutable representation of a job implementation.
 */
public class JobImpl {
  
  /**
   * The id of this job implementation. Unique only within an account.
   */
  public final String id;
  
  /**
   * The account that contains this job implementation.
   */
  public final String account;

  /**
   * A representation of the implementation archive (location of package + hash).
   */
  public final Data archive;

  /**
   * Key/value pairs of metadata.
   */
  public final Map<String, String> metadata;
  
  /**
   * Constructor with all immutable state.
   * 
   * @param id
   * @param account
   * @param archive
   * @param metadata
   */
  public JobImpl(String id, String account, Data archive, Map<String, String> metadata) {
    this.id = id;
    this.account = account;
    this.archive = archive;
    this.metadata = metadata;
  }
}
