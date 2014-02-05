package com.logicblox.steve;

import java.security.PublicKey;

public interface Database
{
  public User getUser(String userid);
  public Account getAccount(String userid);
}
