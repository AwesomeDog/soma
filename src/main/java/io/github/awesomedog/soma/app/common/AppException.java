package io.github.awesomedog.soma.app.common;

import java.util.Objects;

public final class AppException extends RuntimeException {

  private final AppError error;

  public AppException(AppError.Code code, String message, String remediation) {
    this(AppError.of(code, message, remediation));
  }

  public AppException(AppError.Code code, String message, String remediation, Throwable cause) {
    this(AppError.of(code, message, remediation), cause);
  }

  public AppException(AppError error) {
    super(knownErrorMessage(error));
    this.error = error;
  }

  public AppException(AppError error, Throwable cause) {
    super(knownErrorMessage(error) + causeSuffix(error, cause), cause);
    var value = Objects.requireNonNull(error, "error");
    this.error = new AppError(value.code(), getMessage(), value.remediation(), value.details());
  }

  public AppError error() {
    return error;
  }

  private static String knownErrorMessage(AppError error) {
    var value = Objects.requireNonNull(error, "error");
    if (value.code() == AppError.Code.INTERNAL_ERROR) {
      throw new IllegalArgumentException("INTERNAL_ERROR must be created at an output boundary");
    }
    return value.message();
  }

  private static String causeSuffix(AppError error, Throwable cause) {
    if (cause == null || cause.getMessage() == null || cause.getMessage().isBlank()) {
      return "";
    }
    return error.message().contains(cause.getMessage()) ? "" : " " + cause.getMessage();
  }
}
