package io.github.awesomedog.soma.app.common;

import io.micronaut.serde.ObjectMapper;
import java.io.IOException;
import java.io.PrintWriter;

public interface Renderable {

  void render(OutputFormat format, PrintWriter out);

  static void requireTextFormat(OutputFormat format) {
    requireTextFormat(format, "This result supports only text output.");
  }

  static void requireTextFormat(OutputFormat format, String message) {
    if (format != OutputFormat.text) {
      throw new IllegalArgumentException(message);
    }
  }

  static void renderJson(Object value, PrintWriter out, String failureMessage) {
    try {
      out.println(ObjectMapper.getDefault().writeValueAsString(value));
    } catch (IOException e) {
      throw new IllegalStateException(failureMessage, e);
    }
  }
}
