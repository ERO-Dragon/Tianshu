package com.rheinmetal.tianshu.weaving.settings;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.core.config.PropertyModuleConfigStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Where a balance is read from is endpoint data, so it lives in the same entry-override key as the
 * ladder and the request-field compatibility: one line per entry, inherited from the provider profile
 * when absent, and cleared by writing nothing.
 */
class WeavingConfigBalanceTest {

    @TempDir
    Path tempDir;

    @Test
    void anEntryOverrideSurvivesAReload() {
        WeavingConfig config = config();
        LlmBalanceSpec spec = new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.OPENAI_BILLING, "/billing/subscription");

        config.setLlmBalanceSpec("alpha", spec);
        config.save();

        assertEquals(spec, config().getLlmBalanceSpec("alpha"));
    }

    @Test
    void clearingTheOverrideRestoresInheritance() {
        WeavingConfig config = config();
        config.setLlmBalanceSpec("alpha", new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.MOONSHOT, ""));
        config.setLlmBalanceSpec("alpha", null);
        config.save();

        assertNull(config().getLlmBalanceSpec("alpha"));
    }

    @Test
    void theActiveEntryInheritsTheShippedProviderShape() {
        WeavingConfig config = activeEntry("deepseek");

        assertEquals(LlmBalanceSpec.BalanceWire.DEEPSEEK, config.getLlmBalanceSpec().wire(),
                "an entry that overrides nothing uses its provider's documented endpoint");
        assertEquals("/user/balance", config.getLlmBalanceSpec().effectivePath());

        config.setLlmModels(List.of(new WeavingModelEntry(
                "beta", "beta", "moonshot", "https://api.moonshot.cn/v1",
                List.of(new WeavingModelSpec("kimi-k3", "kimi-k3", 1_048_576)), "kimi-k3")));
        config.setLlmActiveModelId("beta");

        assertEquals(LlmBalanceSpec.BalanceWire.MOONSHOT, config.getLlmBalanceSpec().wire());
    }

    @Test
    void aProviderThatPublishesNoBalanceInheritsNone() {
        WeavingConfig config = activeEntry("openai");

        assertEquals(LlmBalanceSpec.NONE, config.getLlmBalanceSpec(),
                "no documented endpoint means no query, not a borrowed one");
    }

    @Test
    void theActiveEntryPrefersItsOwnOverrideOverTheProfile() {
        WeavingConfig config = activeEntry("deepseek");
        LlmBalanceSpec spec = new LlmBalanceSpec(LlmBalanceSpec.BalanceWire.OPENAI_BILLING, "");

        config.setLlmBalanceSpec("alpha", spec);

        assertEquals(spec, config.getLlmBalanceSpec());
    }

    private WeavingConfig activeEntry(String providerId) {
        WeavingConfig config = config();
        config.setLlmModels(List.of(new WeavingModelEntry(
                "alpha", "alpha", providerId, null,
                List.of(new WeavingModelSpec("model", "model", 200_000)), "model")));
        config.setLlmActiveModelId("alpha");
        return config;
    }

    private WeavingConfig config() {
        return new WeavingConfig(
                new PropertyModuleConfigStore(tempDir.resolve("config.properties"), LogSink.NOOP),
                tempDir,
                () -> "en_us"
        );
    }
}