package com.logicblox.steve.db;

import com.logicblox.steve.common.Data;

public class JobImpl
{
  // id is only unique within an account
  public String account;
  public String id;

  public Data archive;
}