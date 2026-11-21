package com.rheinmetal.tianshu.auxilium;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AXAssistantSettingsBoundaryTest {
    @Test
    void commonDefaultHasNoWakeWord() {
        assertEquals("", AuxiliumAssistantSettings.DEFAULT_WAKE_WORD);
        assertEquals("", AuxiliumAssistantSettings.DEFAULT.wakeWord());
    }

    @Test
    void neoforgeClientConfigDoesNotDefaultWakeWord() throws Exception {
        Path clientConfig = findNeoForgeClientConfig();

        String code = Files.readString(clientConfig, StandardCharsets.UTF_8);

        assertFalse(code.contains("builder.define(\"wakeWord\", AuxiliumAssistantSettings.DEFAULT_WAKE_WORD)"));
        assertFalse(code.contains("builder.define(\"wakeWord\", \"天枢\")"));
    }

    private static Path findNeoForgeClientConfig() throws Exception {
        Path adapters = Path.of(System.getProperty("tianshu.workspaceRoot", "../..")).resolve("tianshu-adapter");
        try (Stream<Path> entries = Files.list(adapters)) {
            Optional<Path> config = entries
                    .filter(Files::isDirectory)
                    .filter(path -> path.getFileName().toString().endsWith("-neoforge"))
                    .map(path -> path.resolve("src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java"))
                    .filter(Files::isRegularFile)
                    .findFirst();
            if (config.isEmpty()) {
                throw new IllegalStateException("no NeoForge adapter ClientConfig under " + adapters.toAbsolutePath());
            }
            return config.get();
        }
    }
}
