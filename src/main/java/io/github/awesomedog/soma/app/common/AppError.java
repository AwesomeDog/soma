package io.github.awesomedog.soma.app.common;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.micronaut.serde.annotation.Serdeable;
import java.util.Objects;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AppError(Code code, String message, String remediation, Object details) {

  public AppError {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(message, "message");
  }

  public static AppError of(Code code, String message, String remediation) {
    return new AppError(code, message, remediation, null);
  }

  public enum Code {
    INVALID_REQUEST,
    CONFIG_ERROR,
    NOT_FOUND,
    WRITE_LOCKED,
    OPERATION_FAILED,
    INTERNAL_ERROR
  }
}
