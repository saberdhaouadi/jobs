package com.logicblox.steve.common;

import java.util.Optional;

import java.net.URI;
import java.net.URL;
import java.util.Optional;

/**
 * Combination of a location and optionally a hash for the data.
 */
public final class Data {

  private final String _loc;
  private final Optional<String> _hash;

  public Data(String location, Optional<String> hash) {
    _loc = location;
    _hash = hash;
  }

  public Data(String location, String hash) {
    _loc = location;
    _hash = Optional.ofNullable(hash);
  }

  public Data(String location) {
    this(location, Optional.<String>empty());
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
    return _hash.get();
  }

  public boolean hasHash() {
    return _hash.isPresent();
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
