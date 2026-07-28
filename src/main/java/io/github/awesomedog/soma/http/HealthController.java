package io.github.awesomedog.soma.http;

import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.serde.annotation.Serdeable;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

@Controller
public final class HealthController {

  @Get(uri = "/health", produces = MediaType.APPLICATION_JSON)
  public HealthResponse health() {
    return new HealthResponse(
        "UP", TimeUnit.MILLISECONDS.toSeconds(ManagementFactory.getRuntimeMXBean().getUptime()));
  }

  @Serdeable
  public record HealthResponse(String status, long uptime) {}
}
