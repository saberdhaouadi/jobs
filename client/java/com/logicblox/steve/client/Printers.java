package com.logicblox.steve.client;

import com.logicblox.steve.common.Conversions;
import com.logicblox.steve.protocol.Frontend;

public class Printers
{
  public static void print(Frontend.Status status)
  {
    System.out.printf("%-30s %-12s %-20s %80s %n",
      Conversions.getISO8601(status.getTimestamp()),
      status.getStatusCode(),
      status.getMachine(),
      status.hasMessage() ? status.getMessage() : "");
  }
}
