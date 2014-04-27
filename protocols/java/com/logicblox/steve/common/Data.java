package com.logicblox.steve.common;

import java.net.URI;
import java.net.URL;

public class Data
{
  private String _loc;
  private String _hash;

  public void setLocation(URL url)
  {
    _loc = url.toString();
  }

  public void setLocation(URI uri)
  {
    _loc = uri.toString();
  }

  public void setLocation(String loc)
  {
    _loc = loc;
  }

  public String getLocation()
  {
    return _loc;
  }

  public void setHash(String v)
  {
    _hash = v;
  }

  public String getHash()
  {
    return _hash;
  }

  public boolean hasHash()
  {
    return _hash != null;
  }

  public String toString()
  {
    return _loc + (hasHash() ? " [" + getHash() + "]" : "");
  }
}