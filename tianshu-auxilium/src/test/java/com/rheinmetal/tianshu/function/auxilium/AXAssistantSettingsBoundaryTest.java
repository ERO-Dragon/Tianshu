package com.rheinmetal.tianshu.function.auxilium;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

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
        Path clientConfig = Path.of("../tianshu-adapter/1.21-1.21.1-neoforge/src/main/java/com/rheinmetal/tianshu/neoforge/config/ClientConfig.java");

        String code = Files.readString(clientConfig, StandardCharsets.UTF_8);

        assertFalse(code.contains("builder.define(\"wakeWord\", AuxiliumAssistantSettings.DEFAULT_WAKE_WORD)"));
        assertFalse(code.contains("builder.define(\"wakeWord\", \"天枢\")"));
    }
}
