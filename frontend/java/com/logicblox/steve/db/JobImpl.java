package com.logicblox.steve.db;

import java.util.HashMap;
import java.util.Map;

import com.logicblox.steve.common.Data;

public class JobImpl
{
  // id is only unique within an account
  public String account;
  public String id;

  public Data archive;

  public Map<String, String> tags = new HashMap<String, String>();
}
