package com.logicblox.steve.common;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Date;
import java.util.TimeZone;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

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

  public static Frontend.File convertToFrontendFile(S3File file)
  {
    return 
      Frontend.File.newBuilder()
      .setUrl(getURI(file).toString())
      .setHash("etag:" + file.getETag())
      .build();
  }

  public static Data convertS3FileToData(S3File file)
  {
    Data d = new Data();
    d.setHash("etag:" + file.getETag());
    d.setLocation(getURI(file).toString());
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

  public static Frontend.Param createFrontendParam(String key, String value)
  {
    return
      Frontend.Param.newBuilder()
      .setKey(key)
      .setValue(value)
      .build();
  }

  public static Backend.Param createBackendParam(String key, String value)
  {
    return
      Backend.Param.newBuilder()
      .setKey(key)
      .setValue(value)
      .build();
  }

  public static Map<String, String> createMap(Iterable<Frontend.Param> params)
  {
    Map<String, String> result = new HashMap<String, String>();
    for(Frontend.Param param : params)
      result.put(param.getKey(), param.getValue());
    return result;
  }

  public static String toJSON(Frontend.File file)
  {
    JsonObject o = new JsonObject();
    o.addProperty("url", file.getUrl());
    if(file.hasHash())
      o.addProperty("hash", file.getHash());
    return new Gson().toJson(o);
  }

  public static String toJSON(Frontend.JobImplInfo info)
  {
    JsonObject o = new JsonObject();

    o.addProperty("id", info.getId());

    for(Frontend.Param param : info.getMetadataList())
      o.addProperty(param.getKey(), param.getValue());

    return new Gson().toJson(o);
  }

  public static boolean isComplete(Frontend.State state)
  {
    // TODO refine based on actual state diagram
    return "SUCCEEDED".equals(state.getState()) || "FAILED".equals(state.getState());
  }

  public static String getBasename(Frontend.File file)
  {
    String url = file.getUrl();
    return url.substring(url.lastIndexOf("/") + 1);
  }

  public static URI getURI(S3File file)
  {
    return URI.create("s3://" + file.getBucketName() + "/" + file.getKey());
  }
}
