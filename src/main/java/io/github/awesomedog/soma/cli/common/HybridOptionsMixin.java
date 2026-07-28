package io.github.awesomedog.soma.cli.common;

import picocli.CommandLine.Option;

public final class HybridOptionsMixin {

  @Option(
      names = "--lex",
      paramLabel = "<text>",
      description = "Lexical input; overrides query expansion.")
  String lexicalInput;

  @Option(
      names = "--vec",
      paramLabel = "<text>",
      description = "Vector input; overrides query expansion.")
  String vectorInput;

  @Option(
      names = "--hyde",
      paramLabel = "<text>",
      description = "HyDE passage; overrides query expansion.")
  String hydeInput;

  @Option(
      names = "--intent",
      paramLabel = "<text>",
      description = "Disambiguating background; not searched alone.")
  String intent;

  public String lexicalInput() {
    return lexicalInput;
  }

  public String vectorInput() {
    return vectorInput;
  }

  public String hydeInput() {
    return hydeInput;
  }

  public String intent() {
    return intent;
  }

  public boolean hasAnySearchOption() {
    return lexicalInput != null || vectorInput != null || hydeInput != null || intent != null;
  }
}
