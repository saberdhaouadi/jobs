package com.logicblox.steve.worker;

import org.apache.commons.exec.*;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.commons.io.output.ByteArrayOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;

public class Utils {
  private final static String NIX_STORE_PATH = "/nix/store";
  private final static String NIX_LOG_PATH = "/nix/var/log/nix/drvs";

  public static String nixLogPath(String drv) {
    String basename = FilenameUtils.getBaseName(drv);
    return String.format("%s/%s/%s.drv", NIX_LOG_PATH, basename.substring(0, 2), basename.substring(2));
  }

  public static String streamToString(InputStream stream) throws IOException {
    StringWriter writer = new StringWriter();
    IOUtils.copy(stream, writer);

    return writer.toString().trim();
  }

}
