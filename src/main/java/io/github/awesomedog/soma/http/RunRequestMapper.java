package io.github.awesomedog.soma.http;

import io.github.awesomedog.soma.app.common.AppError;
import io.github.awesomedog.soma.app.common.AppException;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.tree.JsonNode;
import java.util.*;

final class RunRequestMapper {

  private static final Set<String> ALLOWED_GLOBAL_OPTIONS =
      Set.of("verbose", "no-color", "help", "version");
  private static final Set<String> ROOT_OPTION_NAMES =
      Set.of("workspace", "verbose", "no-color", "help", "version");

  private RunRequestMapper() {}

  static String[] toCommandArguments(RunRequest request, Set<String> allowedCommands) {
    var commandName = request.command();
    if (commandName == null || !allowedCommands.contains(commandName)) {
      throw invalid("Unknown command: " + commandName);
    }
    var globalOptionValues =
        request.global() == null ? Map.<String, JsonNode>of() : request.global();
    validateGlobalOptions(globalOptionValues);
    var commandArguments = new ArrayList<String>();
    appendRootOptions(commandArguments, globalOptionValues);
    commandArguments.addAll(List.of(commandName.split("\\.")));
    if (readBoolean(globalOptionValues, "help", false)) {
      commandArguments.add("--help");
    }
    appendCommandOptions(
        commandArguments, request.options() == null ? Map.of() : request.options());
    if (!request.args().isEmpty()) {
      commandArguments.add("--");
      commandArguments.addAll(request.args());
    }
    return commandArguments.toArray(String[]::new);
  }

  private static void validateGlobalOptions(Map<String, JsonNode> globalOptionValues) {
    for (var optionName : globalOptionValues.keySet().stream().sorted().toList()) {
      if (!ALLOWED_GLOBAL_OPTIONS.contains(optionName)) {
        throw invalid("Unknown global option: " + optionName);
      }
      var optionValue = jsonValue(globalOptionValues.get(optionName));
      if (!(optionValue instanceof Boolean)) {
        throw invalid("Global option `" + optionName + "` must be a boolean.");
      }
    }
  }

  private static void appendRootOptions(
      List<String> commandArguments, Map<String, JsonNode> globalOptionValues) {
    if (readBoolean(globalOptionValues, "verbose", false)) {
      commandArguments.add("--verbose");
    }
    if (readBoolean(globalOptionValues, "no-color", true)) {
      commandArguments.add("--no-color");
    }
    if (readBoolean(globalOptionValues, "version", false)) {
      commandArguments.add("--version");
    }
  }

  private static boolean readBoolean(
      Map<String, JsonNode> optionValues, String optionName, boolean defaultValue) {
    var optionValue = jsonValue(optionValues.get(optionName));
    return optionValue == null ? defaultValue : (Boolean) optionValue;
  }

  private static void appendCommandOptions(
      List<String> commandArguments, Map<String, JsonNode> commandOptions) {
    for (var optionName : commandOptions.keySet().stream().sorted().toList()) {
      if (!optionName.matches("[a-z][a-z0-9-]*")) {
        throw invalid("Invalid option name: " + optionName);
      }
      if (ROOT_OPTION_NAMES.contains(optionName)) {
        throw invalid("Reserved option: " + optionName);
      }
      appendCommandOption(commandArguments, optionName, jsonValue(commandOptions.get(optionName)));
    }
  }

  private static void appendCommandOption(
      List<String> commandArguments, String optionName, @Nullable Object optionValue) {
    switch (optionValue) {
      case null -> throw invalid("Option `" + optionName + "` must not be null.");
      case Boolean bool -> {
        appendBooleanOption(commandArguments, optionName, bool);
        return;
      }
      case List<?> optionValues -> {
        for (var listItem : optionValues) {
          if (listItem instanceof Boolean bool) {
            appendBooleanOption(commandArguments, optionName, bool);
          } else if (isScalar(listItem)) {
            commandArguments.add("--" + optionName + "=" + listItem);
          } else {
            throw invalid("Option `" + optionName + "` must be an array of scalar values.");
          }
        }
        return;
      }
      default -> {}
    }
    if (!isScalar(optionValue)) {
      throw invalid("Option `" + optionName + "` must be a scalar value.");
    }
    commandArguments.add("--" + optionName + "=" + optionValue);
  }

  private static void appendBooleanOption(
      List<String> commandArguments, String optionName, boolean enabled) {
    if (!enabled) {
      throw invalid(
          "Boolean option `" + optionName + "` must be true; omit it to disable the flag.");
    }
    commandArguments.add("--" + optionName);
  }

  private static boolean isScalar(@Nullable Object optionValue) {
    return optionValue instanceof String || optionValue instanceof Number;
  }

  private static @Nullable Object jsonValue(@Nullable JsonNode jsonNode) {
    return jsonNode == null ? null : jsonNode.getValue();
  }

  private static AppException invalid(String message) {
    return new AppException(AppError.Code.INVALID_REQUEST, message, null);
  }
}
