package com.logicblox.steve.common;

import com.logicblox.common.Option;

import java.net.URI;
import java.net.URL;

/**
 * Combination of a location and optionally a hash for the data.
 */
public final class Data
{
  private final String _loc;
  private final Option<String> _hash;

  public Data(String location, Option<String> hash)
  {
    _loc = location;
    _hash = hash;
  }

  public Data(String location)
  {
    this(location, Option.<String>none());
  }

  public Data(URL location)
  {
    this(location.toString());
  }

  public Data(URI location)
  {
    this(location.toString());
  }

  public String getLocation()
  {
    return _loc;
  }

  public String getHash()
  {
    return _hash.unwrap();
  }

  public boolean hasHash()
  {
    return _hash.isSome();
  }

  public String toString()
  {
    return _loc + (hasHash() ? " [" + getHash() + "]" : "");
  }
}
