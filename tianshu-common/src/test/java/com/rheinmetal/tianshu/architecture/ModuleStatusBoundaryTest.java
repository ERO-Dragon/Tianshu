package com.rheinmetal.tianshu.architecture;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModuleStatusBoundaryTest {
    @Test
    void commonStatusContractDoesNotCarryLocalizedFallbackText() throws Exception {
        for (String relative : new String[]{
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/status/ModuleStatus.java",
                "tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/status/ModuleStatuses.java"
        }) {
            Path source = resolveFromWorkspace(Path.of(relative));
            String text = Files.readString(source, StandardCharsets.UTF_8);
            assertFalse(text.contains("fallbackMessage"), relative);
        }
    }

    private static Path resolveFromWorkspace(Path relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return relativePath;
    }
}
