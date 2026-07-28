package io.github.awesomedog.soma.app.system;

import static io.github.awesomedog.soma.app.common.Renderable.requireTextFormat;

import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.Renderable;
import io.micronaut.serde.annotation.Serdeable;
import java.io.PrintWriter;
import java.util.List;

@Serdeable
public record SyncReport(List<OperationReport> phases) implements Renderable {

  public SyncReport {
    phases = List.copyOf(phases);
  }

  @Override
  public void render(OutputFormat format, PrintWriter out) {
    requireTextFormat(format, "System results support only text output.");
    for (var phasePosition = 0; phasePosition < phases.size(); phasePosition++) {
      if (phasePosition > 0) {
        out.println();
      }
      out.println(phases.get(phasePosition).message());
    }
  }
}
