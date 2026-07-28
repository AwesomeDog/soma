package io.github.awesomedog.soma.cli.common;

import io.github.awesomedog.soma.app.common.ProgressEvent;
import io.github.awesomedog.soma.exec.Invocation;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

@Command
public abstract class CliCommand implements Callable<Integer> {

  @Spec protected CommandSpec spec;

  protected static Consumer<ProgressEvent> progress(Invocation invocation) {
    return invocation::progress;
  }

  protected final CommandSpec leafSpec() {
    var parsed = spec.root().commandLine().getParseResult();
    while (parsed.subcommand() != null) {
      parsed = parsed.subcommand();
    }
    return parsed.commandSpec();
  }

  @Override
  public abstract Integer call();
}
