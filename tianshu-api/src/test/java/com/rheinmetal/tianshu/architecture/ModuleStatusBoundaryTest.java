package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.testkit.WorkspacePaths;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModuleStatusBoundaryTest {
    @Test
    void commonStatusContractDoesNotCarryLocalizedFallbackText() throws Exception {
        for (String relative : new String[]{
                "tianshu-api/src/main/java/com/rheinmetal/tianshu/protocol/status/ModuleStatus.java",
                "tianshu-api/src/main/java/com/rheinmetal/tianshu/protocol/status/ModuleStatuses.java"
        }) {
            Path source = resolveFromWorkspace(Path.of(relative));
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("fallbackMessage"), relative);
        }
    }

    @Test
    void moduleStatusDoesNotCarryProductHudTags() throws Exception {
        for (String relative : new String[]{
                "tianshu-api/src/main/java/com/rheinmetal/tianshu/protocol/status/ModuleStatuses.java",
                "tianshu-auxilium/src/main/java/com/rheinmetal/tianshu/auxilium/core/turn/AXTurnStatusPublisher.java",
                "tianshu-auxilium/src/main/java/com/rheinmetal/tianshu/auxilium/module/memory/maintenance/AXMemoryMaintenanceService.java"
        }) {
            Path source = resolveFromWorkspace(Path.of(relative));
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("presenceStatusType"), relative);
        }
    }

    private static Path resolveFromWorkspace(Path relativePath) {
        return WorkspacePaths.require(relativePath.toString());
    }
}
