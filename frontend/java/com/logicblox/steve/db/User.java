package com.logicblox.steve.db;

/**
 * Immutable User representation.
 */
public final class User {
  
  private final String _id;
  private final String _accountId;
  private final String _publicKey;

  public User(String id, String accountId, String publicKey) {
    _id = id;
    _accountId = accountId;
    _publicKey = publicKey;
  }

  public String getId() {
    return _id;
  }

  public String getAccountId() {
    return _accountId;
  }

  public String getPublicKey() {
    return _publicKey;
  }
  
  @Override
  public String toString() {
    return _id + "(" + _accountId + ")";
  }
}