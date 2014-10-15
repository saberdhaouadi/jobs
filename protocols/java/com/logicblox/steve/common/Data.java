package com.logicblox.steve.common;

import com.logicblox.common.Option;

import java.net.URI;
import java.net.URL;

/**
 * Combination of a location and optionally a hash for the data.
 */
public final class Data {
  
  private final String _loc;
  private final Option<String> _hash;

  public Data(String location, Option<String> hash) {
    _loc = location;
    _hash = hash;
  }
  
  public Data(String location, String hash) {
    _loc = location;
    _hash = Option.wrap(hash);
  }

  public Data(String location) {
    this(location, Option.<String>none());
  }

  public Data(URL location) {
    this(location.toString());
  }

  public Data(URI location) {
    this(location.toString());
  }

  public String getLocation() {
    return _loc;
  }

  public String getHash() {
    return _hash.unwrap();
  }

  public boolean hasHash() {
    return _hash.isSome();
  }

  public String toString() {
    return _loc + (hasHash() ? " [" + getHash() + "]" : "");
  }

  @Override
  public int hashCode() {
    final int prime = 31;
    int result = 1;
    result = prime * result + ((_hash == null) ? 0 : _hash.hashCode());
    result = prime * result + ((_loc == null) ? 0 : _loc.hashCode());
    return result;
  }

  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    Data other = (Data) obj;
    if (_hash == null) {
      if (other._hash != null)
        return false;
    } else if (!_hash.equals(other._hash))
      return false;
    if (_loc == null) {
      if (other._loc != null)
        return false;
    } else if (!_loc.equals(other._loc))
      return false;
    return true;
  }
  
  
}
