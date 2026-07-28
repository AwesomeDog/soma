package io.github.awesomedog.soma.exec;

import static io.github.awesomedog.soma.app.common.AppError.Code.OPERATION_FAILED;

import io.github.awesomedog.soma.app.common.AppException;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

@Singleton
public final class ActiveWorkspace {

  public enum Source {
    FLAG,
    ENVIRONMENT,
    DIRECTORY_LOCAL,
    DEFAULT
  }

  private final WorkspaceResolver workspaceResolver;
  private final AtomicReference<WorkspaceSelection> selectedWorkspace = new AtomicReference<>();

  @Inject
  ActiveWorkspace(WorkspaceResolver workspaceResolver) {
    this.workspaceResolver = workspaceResolver;
  }

  public void selectWorkspace(String requestedWorkspaceName) {
    var currentSelection = selectedWorkspace.get();
    if (currentSelection != null && requestedWorkspaceName == null) {
      return;
    }
    var resolvedWorkspace = workspaceResolver.resolveWorkspace(requestedWorkspaceName);
    if (requestedWorkspaceName == null) {
      publishFirstImplicitSelection(resolvedWorkspace);
    } else {
      publishAndRejectDifferentSelection(resolvedWorkspace);
    }
  }

  public void selectWorkspaceForInit() {
    publishAndRejectDifferentSelection(workspaceResolver.resolveDirectoryLocalWorkspaceForInit());
  }

  public String workspaceName() {
    return requireSelectedWorkspace().workspaceName();
  }

  public Source selectionSource() {
    return requireSelectedWorkspace().selectionSource();
  }

  public Path configFile() {
    return requireSelectedWorkspace().configFile();
  }

  public Path dbFile() {
    return requireSelectedWorkspace().dbFile();
  }

  public Path logFile() {
    return requireSelectedWorkspace().logFile();
  }

  public Path lockFile() {
    return requireSelectedWorkspace().lockFile();
  }

  private void publishFirstImplicitSelection(WorkspaceSelection resolvedWorkspace) {
    // Unspecified workspace resolution may race across HTTP requests; the first candidate wins.
    selectedWorkspace.compareAndExchange(null, resolvedWorkspace);
  }

  private void publishAndRejectDifferentSelection(WorkspaceSelection resolvedWorkspace) {
    var existingSelection = selectedWorkspace.compareAndExchange(null, resolvedWorkspace);
    if (existingSelection == null || sameSelection(existingSelection, resolvedWorkspace)) {
      return;
    }
    throw new AppException(
        OPERATION_FAILED,
        "This Soma process already selected workspace `" + existingSelection.workspaceName() + "`.",
        "Start a separate Soma process to use another workspace.");
  }

  private static boolean sameSelection(WorkspaceSelection left, WorkspaceSelection right) {
    return left.workspaceName().equals(right.workspaceName())
        && left.configFile().equals(right.configFile())
        && left.dbFile().equals(right.dbFile())
        && left.logFile().equals(right.logFile())
        && left.lockFile().equals(right.lockFile());
  }

  private WorkspaceSelection requireSelectedWorkspace() {
    var currentSelection = selectedWorkspace.get();
    if (currentSelection == null) {
      throw new IllegalStateException("workspace is not selected - run via CommandRunner");
    }
    return currentSelection;
  }
}
