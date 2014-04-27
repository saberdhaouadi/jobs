package com.logicblox.steve.db;

public class User
{
  private final String _id;
  private final String _accountId;
  private final String _publicKey;

  public User(String id, String accountId, String publicKey)
  {
    _id = id;
    _accountId = accountId;
    _publicKey = publicKey;
  }

  public String getId()
  {
    return _id;
  }

  public String getAccountId()
  {
    return _accountId;
  }

  public String getPublicKey()
  {
    return _publicKey;
  }
}