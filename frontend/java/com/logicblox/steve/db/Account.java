package com.logicblox.steve.db;

public class Account
{
  private final String _id;

  public Account(String id)
  {
    _id = id;
  }
  
  public String getId()
  {
    return _id;
  }
}
