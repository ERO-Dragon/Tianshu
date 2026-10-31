package com.rheinmetal.tianshu.core;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class TianshuCoreManagerBoundaryTest {
    @Test
    void coreManagerDoesNotExposeFullProtocolRuntime() throws Exception {
        String code = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/core/TianshuCoreManager.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(code.contains("public ProtocolRuntime protocolRuntime()"));
        assertFalse(code.contains("public ProtocolRuntime getProtocolRuntime()"));
    }

    @Test
    void neoforgeDoesNotBypassCoreManagerBoundary() throws Exception {
        Path adapterRoot = Path.of("../tianshu-adapter");
        try (Stream<Path> files = Files.walk(adapterRoot)) {
            String bypasses = files
                    .filter(path -> path.toString().endsWith(".java"))
                    .filter(path -> path.toString().replace('\\', '/').contains("/src/main/java/"))
                    .filter(path -> !path.toString().replace('\\', '/').contains("/build/"))
                    .filter(path -> contains(path, "coreManager.protocolRuntime()") || contains(path, "coreManager.getProtocolRuntime()"))
                    .map(adapterRoot::relativize)
                    .map(Path::toString)
                    .sorted()
                    .reduce("", (left, right) -> left.isEmpty() ? right : left + "\n" + right);

            assertFalse(!bypasses.isBlank(), bypasses);
        }
    }

    private static boolean contains(Path path, String pattern) {
        try {
            return Files.readString(path, StandardCharsets.UTF_8).contains(pattern);
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to read " + path, exception);
        }
    }
}
