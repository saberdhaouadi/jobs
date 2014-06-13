package com.logicblox.steve.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Date;
import java.util.TimeZone;

import java.text.SimpleDateFormat;

import com.logicblox.s3lib.S3File;
import com.logicblox.steve.protocol.Frontend;
import com.logicblox.steve.protocol.Backend;

public class Conversions
{
  public static List<Data> convertFrontendFileToData(List<Frontend.File> files)
  {
    List<Data> result = new ArrayList<Data>();

    for(Frontend.File f : files)
      result.add(convertFileToData(f));

    return result;
  }

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

  public static Backend.File convertDataToBackendFile(Data d)
  {
    Backend.File.Builder f = Backend.File.newBuilder();
    f.setUrl(d.getLocation());
    if(d.hasHash())
      f.setHash(d.getHash());
    return f.build();
  }

  public static Frontend.File convertDataToFrontendFile(Data d)
  {
    Frontend.File.Builder f = Frontend.File.newBuilder();
    f.setUrl(d.getLocation());
    if(d.hasHash())
      f.setHash(d.getHash());
    return f.build();
  }

  public static Data convertFileToData(Frontend.File file)
  {
    Data d = new Data();
    d.setLocation(file.getUrl());
    if(file.hasHash())
      d.setHash(file.getHash());

    return d;
  }

  public static Data convertS3FileToData(S3File file)
  {
    Data d = new Data();
    d.setHash("etag:" + file.getETag());
    d.setLocation("s3://" + file.getBucketName() + "/" + file.getKey());
    return d;
  }

  public static String getCurrentISO8601()
  {
    return getISO8601(new Date());
  }

  public static String getISO8601(long stamp)
  {
    return getISO8601(new Date(stamp));
  }

  public static String getISO8601(Date date)
  {
    SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS+00:00");
    format.setTimeZone(TimeZone.getTimeZone("UTC"));
    return format.format(date);
  }
}
