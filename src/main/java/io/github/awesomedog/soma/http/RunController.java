package io.github.awesomedog.soma.http;

import io.micronaut.core.bind.exceptions.UnsatisfiedArgumentException;
import io.micronaut.core.convert.exceptions.ConversionErrorException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Error;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.codec.CodecException;
import io.micronaut.json.JsonSyntaxException;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.serde.exceptions.SerdeException;
import io.micronaut.web.router.exceptions.UnsatisfiedRouteException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Controller("/api")
public final class RunController {

  private static final Logger LOG = LoggerFactory.getLogger(RunController.class);
  private static final Set<String> ALLOWED_COMMANDS =
      Set.of(
          "project.list",
          "project.files",
          "project.add",
          "project.update",
          "project.remove",
          "project.rename",
          "project.show",
          "search.hybrid",
          "search.lexical",
          "search.vector",
          "get",
          "context.list",
          "context.set",
          "context.remove",
          "status");
  private final RpcRunner rpcRunner;

  public RunController(RpcRunner rpcRunner) {
    this.rpcRunner = rpcRunner;
  }

  @Post(uri = "/run", consumes = MediaType.APPLICATION_JSON, produces = MediaType.APPLICATION_JSON)
  @ExecuteOn(TaskExecutors.BLOCKING)
  public RunResponse run(@Body RunRequest request) {
    return rpcRunner.run(request, ALLOWED_COMMANDS);
  }

  @Error(exception = Exception.class)
  public HttpResponse<RunResponse> requestError(Exception exception) {
    var bindingFailure = isBindingFailure(exception);
    if (!bindingFailure) {
      LOG.error("Unhandled HTTP request failure", exception);
    }
    var error =
        bindingFailure
            ? RpcRunner.invalidRequest("Invalid RPC request. " + exception)
            : RpcRunner.internalError(exception);
    return HttpResponse.ok(RpcRunner.failure(error));
  }

  private static boolean isBindingFailure(Throwable failure) {
    for (var cause = failure;
        cause != null && cause.getCause() != cause;
        cause = cause.getCause()) {
      if (cause instanceof UnsatisfiedArgumentException
          || cause instanceof UnsatisfiedRouteException
          || cause instanceof ConversionErrorException
          || cause instanceof CodecException
          || cause instanceof JsonSyntaxException
          || cause instanceof SerdeException) {
        return true;
      }
    }
    return false;
  }
}
