package com.logicblox.steve.common;

import java.net.URI;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.TimeZone;
import java.util.Optional;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableList.Builder;
import com.google.common.collect.ImmutableMap;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.logicblox.cloudstore.StoreFile;
import com.logicblox.steve.common.Status.Event;
import com.logicblox.steve.protocol.Backend;
import com.logicblox.steve.protocol.Database;
import com.logicblox.steve.protocol.Frontend;

/**
 * Utilities to convert data across the different protobuf protocols and Java representations.
 */
public class Conversions {

  //
  // SOME GENERIC HELPERS
  //

  public static final SimpleDateFormat iso8601Format =
          new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS+00:00");

  static {
    iso8601Format.setTimeZone(TimeZone.getTimeZone("UTC"));
  }

  public static Data convertStoreFileToData(StoreFile file) {
    return new Data(getURI(file).toString(), Optional.of("etag:" + file.getETag()));
  }

  public static String getCurrentISO8601() {
    return getISO8601(new Date());
  }

  public static String getISO8601(long stamp) {
    return getISO8601(new Date(stamp));
  }

  public static String getISO8601(Date date) {
    return iso8601Format.format(date);
  }

  public static URI getURI(StoreFile file) {
    return URI.create("s3://" + file.getBucketName() + "/" + file.getObjectKey());
  }


  //
  // FRONTEND
  //

  public static Frontend.File convertDataToFrontendFile(Data d) {
    final Frontend.File.Builder f = Frontend.File.newBuilder();
    f.setUrl(d.getLocation());
    if (d.hasHash())
      f.setHash(d.getHash());
    return f.build();
  }

  public static List<Data> convertFrontendFileToData(List<Frontend.File> files) {
    final List<Data> result = new ArrayList<Data>();

    for (Frontend.File f : files)
      result.add(convertFileToData(f));

    return result;
  }


  public static Frontend.Param createFrontendParam(String key, String value) {
    return Frontend.Param.newBuilder()
            .setKey(key)
            .setValue(value)
            .build();
  }

  public static Data convertFileToData(Frontend.File file) {
    return new Data(file.getUrl(), Optional.ofNullable(file.hasHash() ? file.getHash() : null));
  }

  public static Frontend.File convertToFrontendFile(StoreFile file) {
    return Frontend.File.newBuilder()
            .setUrl(getURI(file).toString())
            .setHash("etag:" + file.getETag())
            .build();
  }

  public static Map<String, String> createMap(Iterable<Frontend.Param> params) {
    final Map<String, String> result = new HashMap<String, String>();
    for (Frontend.Param param : params)
      result.put(param.getKey(), param.getValue());
    return result;
  }

  public static String toJSON(Frontend.File file) {
    final JsonObject o = new JsonObject();
    o.addProperty("url", file.getUrl());
    if (file.hasHash())
      o.addProperty("hash", file.getHash());
    return new Gson().toJson(o);
  }

  public static String toJSON(Frontend.JobImplInfo info) {
    final JsonObject o = new JsonObject();

    o.addProperty("id", info.getId());

    for (Frontend.Param param : info.getMetadataList())
      o.addProperty(param.getKey(), param.getValue());

    return new Gson().toJson(o);
  }

  public static boolean isComplete(Frontend.State state) {
    // TODO refine based on actual state diagram
    return "SUCCEEDED".equals(state.getState()) || "FAILED".equals(state.getState());
  }

  public static String getBasename(Frontend.File file) {
    final String url = file.getUrl();
    return url.substring(url.lastIndexOf("/") + 1);
  }

  //
  // BACKEND
  //

  public static Backend.Param createBackendParam(String key, String value) {
    return Backend.Param.newBuilder()
            .setKey(key)
            .setValue(value)
            .build();
  }


  public static List<Data> convertFileToData(List<Backend.File> files) {
    final List<Data> result = new ArrayList<Data>();

    for (Backend.File f : files)
      result.add(convertFileToData(f));

    return result;
  }

  public static Data convertFileToData(Backend.File file) {
    return new Data(file.getUrl(), Optional.ofNullable(file.hasHash() ? file.getHash() : null));
  }

  public static Backend.File convertDataToBackendFile(Data d) {
    final Backend.File.Builder f = Backend.File.newBuilder();
    f.setUrl(d.getLocation());
    if (d.hasHash())
      f.setHash(d.getHash());
    return f.build();
  }


  //
  // DATABASE
  //


  // single

  public static Database.File convertToDatabaseFile(Data d) {
    final Database.File.Builder f = Database.File.newBuilder();
    f.setUrl(d.getLocation());
    if (d.hasHash())
      f.setHash(d.getHash());
    return f.build();
  }

  public static Data convertFromDatabaseFile(Database.File d) {
    return new Data(d.getUrl(), d.hasHash() ? Optional.ofNullable(d.getHash()) : Optional.<String>empty());
  }

  public static Collection<Database.Param> convertToDatabaseParams(Map<String, String> input) {
    final Builder<Database.Param> result = ImmutableList.builder();
    for (Entry<String, String> e : input.entrySet())
      result.add(createDatabaseParam(e.getKey(), e.getValue()));
    return result.build();
  }

  public static Map<String, String> convertFromDatabaseParams(Collection<Database.Param> params) {
    final ImmutableMap.Builder<String, String> result = ImmutableMap.builder();
    for (Database.Param param : params)
      result.put(param.getKey(), param.getValue());
    return result.build();
  }

  public static Database.Status convertToDatabaseStatus(Status status) {
    Database.Status.Builder res = Database.Status.newBuilder()
            .setTimestamp(status.timestamp)
            .setEvent(status.event.toString())
            .setMachine(status.machine)
            .setMessage(status.hasMessage() ? status.message : "");
    if(status.cpuUsage != 0)
        res.setCpuUsage(status.cpuUsage);
    if(status.maxMemory !=0)
        res.setMaxMemory(status.maxMemory);
    if(status.maxDiskUsage != 0)
        res.setMaxDiskUsage(status.maxDiskUsage);
    return res.build();
  }

  public static Status convertFromDatabaseStatus(Database.Status status) {
    return new Status(
            status.getTimestamp(),
            Event.valueOf(status.getEvent()),
            status.getMachine(),
            status.getMessage(), 0, 0, 0);
  }

  // multiple

  public static Collection<Database.File> convertToDatabaseFiles(Collection<Data> input) {
    final Builder<Database.File> result = ImmutableList.builder();
    for (Data d : input)
      result.add(convertToDatabaseFile(d));
    return result.build();
  }

  public static Collection<Data> convertFromDatabaseFiles(Collection<Database.File> input) {
    final Builder<Data> result = ImmutableList.builder();
    for (Database.File d : input)
      result.add(convertFromDatabaseFile(d));
    return result.build();
  }

  public static List<Status> convertFromDatabaseStatus(List<Database.Status> input) {
    final Builder<Status> result = ImmutableList.builder();
    for (Database.Status d : input)
      result.add(convertFromDatabaseStatus(d));
    return result.build();
  }

  public static Database.Param createDatabaseParam(String key, String value) {
    return Database.Param.newBuilder()
            .setKey(key)
            .setValue(value)
            .build();
  }


}
