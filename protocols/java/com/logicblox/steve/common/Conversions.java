package com.logicblox.steve.common;

import java.util.ArrayList;
import java.util.List;

import com.logicblox.steve.protocol.Frontend;
import com.logicblox.steve.protocol.Backend;

public class Conversions
{
  public static List<Data> convertFileToData(List<Backend.File> files)
  {
    List<Data> result = new ArrayList<Data>();

    for(Backend.File f : files)
      result.add(convertFileToData(f));

    return result;
  }

  public static Data convertFileToData(Backend.File file)
  {
    Data d = new Data();
    d.setLocation(file.getUrl());
    if(file.hasHash())
      d.setHash(file.getHash());

    return d;
  }
}