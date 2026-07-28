package io.github.awesomedog.soma.exec;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.DisplayFormat;
import io.github.awesomedog.soma.app.common.OutputFormat;
import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.app.common.Renderable;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.util.Locale;
import java.util.Objects;

public final class Invocation {

  private static final int PROGRESS_BAR_WIDTH = 30;
  private static final long PLAIN_PROGRESS_INTERVAL_NANOS = Duration.ofSeconds(1).toNanos();

  private final PrintWriter out;
  private final PrintWriter err;
  private final boolean tty;
  private final StringWriter stdoutCapture;
  private final StringWriter stderrCapture;
  private Object result;
  private AppError recordedError;
  private int progressLineWidth = -1;
  private ProgressEvent currentProgress;
  private long lastPlainProgressNanos;

  private Invocation(
      PrintWriter out,
      PrintWriter err,
      boolean tty,
      StringWriter stdoutCapture,
      StringWriter stderrCapture) {
    this.out = Objects.requireNonNull(out, "out");
    this.err = Objects.requireNonNull(err, "err");
    this.tty = tty;
    this.stdoutCapture = stdoutCapture;
    this.stderrCapture = stderrCapture;
  }

  public static Invocation cli(boolean tty) {
    return new Invocation(
        new PrintWriter(System.out, true), new PrintWriter(System.err, true), tty, null, null);
  }

  public static Invocation captured() {
    var stdout = new StringWriter();
    var stderr = new StringWriter();
    return new Invocation(
        new PrintWriter(stdout, true), new PrintWriter(stderr, true), false, stdout, stderr);
  }

  public PrintWriter out() {
    return out;
  }

  public PrintWriter err() {
    return err;
  }

  public boolean isTty() {
    return tty;
  }

  public boolean capturesOutput() {
    return stdoutCapture != null;
  }

  public Object result() {
    return result;
  }

  public AppError recordedError() {
    return recordedError;
  }

  public void emit(Renderable result, OutputFormat format) {
    finishProgress();
    Objects.requireNonNull(result, "result").render(Objects.requireNonNull(format, "format"), out);
    out.flush();
    this.result = result;
  }

  public void progress(ProgressEvent event) {
    Objects.requireNonNull(event, "event");
    if (event.completed() == null) {
      if (tty) {
        writeProgressLine(event.message());
      } else {
        err.println(event.message());
        err.flush();
      }
      return;
    }
    if (!tty) {
      writePlainProgress(event);
      return;
    }
    if (progressLineWidth >= 0 && !sameProgress(event)) {
      finishProgress();
    }
    currentProgress = event;
    var line =
        event.total() > 0 && event.unit() == ProgressEvent.WorkUnit.FILES
            ? event.message() + ": " + event.completed() + "/" + event.total()
            : progressLine(event);
    writeProgressLine(line);
    if (complete(event)) {
      finishProgress();
    }
  }

  public void finishProgress() {
    if (tty && progressLineWidth >= 0) {
      err.println();
      err.flush();
    }
    progressLineWidth = -1;
    currentProgress = null;
    lastPlainProgressNanos = 0;
  }

  public void recordError(AppError error) {
    recordedError = Objects.requireNonNull(error, "error");
  }

  public String stdout() {
    return captured(stdoutCapture, out, "stdout");
  }

  public String stderr() {
    return captured(stderrCapture, err, "stderr");
  }

  private static String captured(StringWriter capture, PrintWriter writer, String stream) {
    if (capture == null) {
      throw new IllegalStateException(stream + " is not captured for CLI invocations");
    }
    writer.flush();
    return capture.toString();
  }

  private void writePlainProgress(ProgressEvent event) {
    var nowNanos = System.nanoTime();
    var notChanged = sameProgress(event);
    var complete = complete(event);
    if (notChanged
        && !complete
        && nowNanos - lastPlainProgressNanos < PLAIN_PROGRESS_INTERVAL_NANOS) {
      return;
    }
    currentProgress = complete ? null : event;
    lastPlainProgressNanos = nowNanos;
    err.println(plainProgressLine(event));
    err.flush();
  }

  private boolean sameProgress(ProgressEvent event) {
    return currentProgress != null
        && currentProgress.message().equals(event.message())
        && currentProgress.unit() == event.unit();
  }

  private void writeProgressLine(String line) {
    err.print('\r');
    err.print(line);
    if (progressLineWidth > line.length()) {
      err.print(" ".repeat(progressLineWidth - line.length()));
    }
    err.flush();
    progressLineWidth = line.length();
  }

  private static String plainProgressLine(ProgressEvent event) {
    var label = event.message();
    var completed = event.completed();
    var total = event.total();
    if (event.unit() == ProgressEvent.WorkUnit.FILES && total > 0) {
      return label + ": " + completed + "/" + total;
    }
    var line = label + ": " + measure(event);
    return total > 0 ? line + " (" + (int) (fraction(event) * 100) + "%)" : line;
  }

  private static String progressLine(ProgressEvent event) {
    var line = new StringBuilder("  ").append(event.message()).append("  ");
    if (event.total() > 0) {
      var fraction = fraction(event);
      line.append(progressBar(fraction));
      line.append(
          String.format(Locale.ROOT, "  %3d%%  %s", (int) (fraction * 100), measure(event)));
    } else {
      line.append(measure(event));
    }
    return line.toString();
  }

  private static String measure(ProgressEvent event) {
    var completed = event.completed();
    var total = event.total();
    if (event.unit() == ProgressEvent.WorkUnit.BYTES) {
      return total > 0
          ? DisplayFormat.bytes(completed) + " / " + DisplayFormat.bytes(total)
          : DisplayFormat.bytes(completed);
    }
    var label = event.unit().name().toLowerCase(Locale.ROOT);
    return total > 0 ? completed + " / " + total + " " + label : completed + " " + label;
  }

  private static String progressBar(double fraction) {
    var filled = (int) (fraction * PROGRESS_BAR_WIDTH);
    var remaining =
        filled == PROGRESS_BAR_WIDTH ? "" : ">" + " ".repeat(PROGRESS_BAR_WIDTH - filled - 1);
    return "[" + "=".repeat(filled) + remaining + "]";
  }

  private static boolean complete(ProgressEvent event) {
    return event.total() >= 0 && event.completed() >= event.total();
  }

  private static double fraction(ProgressEvent event) {
    return event.total() <= 0 ? 0 : Math.min(1, (double) event.completed() / event.total());
  }
}
