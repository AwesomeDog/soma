package io.github.awesomedog.soma.app.system;

import io.github.awesomedog.soma.app.ports.WorkspaceIndex;
import io.github.awesomedog.soma.app.ports.WriteLock;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

@Singleton
public final class IndexCleanup {

  private final WorkspaceIndex workspaceIndex;

  public IndexCleanup(WorkspaceIndex workspaceIndex) {
    this.workspaceIndex = Objects.requireNonNull(workspaceIndex, "workspaceIndex");
  }

  public OperationReport removeOrphans(Path databaseFile, WriteLock.Token token) {
    workspaceIndex.openExistingForWrite(databaseFile, token);
    var removedContents = workspaceIndex.cleanOrphans();
    return new OperationReport(
        "clean",
        removedContents == 0
            ? "No orphaned index records to remove."
            : "Removed orphaned index data for "
                + removedContents
                + " unreferenced content record(s).",
        Map.of("contents", removedContents));
  }
}
