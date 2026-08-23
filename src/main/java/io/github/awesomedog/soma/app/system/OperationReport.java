package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;

import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.micronaut.serde.annotation.Serdeable;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Serdeable
public record OperationReport(String action, String message, Map<String, Integer> counts)
    implements Renderable {

  public OperationReport {
    counts = Collections.unmodifiableMap(new LinkedHashMap<>(counts));
  }

  public OperationReport withDuration(long elapsedMilliseconds) {
    var summary = message.endsWith(".") ? message.substring(0, message.length() - 1) : message;
    return new OperationReport(
        action, summary + " in " + DisplayFormat.duration(elapsedMilliseconds) + ".", counts);
  }

  @Override
  public void render(OutputFormat format, PrintWriter out) {
    requireTextFormat(format, "System results support only text output.");
    out.println(message);
  }
}
