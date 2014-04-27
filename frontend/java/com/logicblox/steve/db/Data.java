package com.logicblox.steve.db;

import java.net.URL;

public class Data
{
  private URL _url;
  private String _hash;

  public void setURL(URL url)
  {
    _url = url;
  }

  public URL getURL()
  {
    return _url;
  }

  public void setHash(String v)
  {
    _hash = v;
  }

  public String getHash()
  {
    return _hash;
  }
}