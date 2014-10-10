package com.logicblox.steve.db;

import java.util.Map;

import com.logicblox.steve.common.Data;

public class JobImpl
{
  // id is only unique within an account
  public final String id;
  public final String account;

  public final Data archive;

  public final Map<String, String> metadata;
  
  public JobImpl(String id) {
    this.id = id;
    this.account = null;
    this.archive = null;
    this.metadata = null;
  }

  public JobImpl(String id, String account, Data archive, Map<String, String> metadata) {
    this.id = id;
    this.account = account;
    this.archive = archive;
    this.metadata = metadata;
  }
}
