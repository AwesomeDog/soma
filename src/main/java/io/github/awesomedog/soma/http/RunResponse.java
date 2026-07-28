package io.github.awesomedog.soma.http;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.awesomedog.soma.app.common.AppError;
import io.micronaut.serde.annotation.Serdeable;

@Serdeable
@JsonInclude(JsonInclude.Include.ALWAYS)
public record RunResponse(
    boolean success,
    String requestId,
    long durationMs,
    int exitCode,
    Object data,
    String stdout,
    String stderr,
    AppError error) {}
