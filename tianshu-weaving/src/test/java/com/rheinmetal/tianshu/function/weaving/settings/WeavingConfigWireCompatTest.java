package com.rheinmetal.tianshu.function.weaving.settings;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.core.config.PropertyModuleConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The wire compatibility is endpoint data, so it lives in the same entry-override key as the reasoning
 * ladder: one line per entry, inherited when absent.
 */
class WeavingConfigWireCompatTest {

    @TempDir
    Path tempDir;

    @Test
    void anEntryOverrideSurvivesAReload() {
        WeavingConfig config = config();
        LlmWireCompat compat = new LlmWireCompat(false, LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS);

        config.setLlmWireCompat("alpha", compat);
        config.save();

        assertEquals(compat, config().getLlmWireCompat("alpha"));
    }

    @Test
    void clearingTheOverrideRestoresInheritance() {
        WeavingConfig config = config();
        config.setLlmWireCompat("alpha", new LlmWireCompat(false, LlmWireCompat.MaxTokensField.MAX_COMPLETION_TOKENS));
        config.setLlmWireCompat("alpha", null);
        config.save();

        assertNull(config().getLlmWireCompat("alpha"));
    }

    @Test
    void theActiveEntryInheritsTheShippedProviderDefault() {
        WeavingConfig config = config();
        config.setLlmModels(List.of(new WeavingModelEntry(
                "alpha", "alpha", "deepseek", "https://api.deepseek.com/v1",
                List.of(new WeavingModelSpec("deepseek-flash", "deepseek-flash", 102_400)), "deepseek-flash")));
        config.setLlmActiveModelId("alpha");

        assertEquals(LlmWireCompat.DEFAULT, config.getLlmWireCompat(),
                "the shipped provider profile defines the compatibility of an entry that overrides nothing");
    }

    private WeavingConfig config() {
        return new WeavingConfig(
                new PropertyModuleConfigStore(tempDir.resolve("config.properties"), LogSink.NOOP),
                tempDir,
                () -> "en_us"
        );
    }
}