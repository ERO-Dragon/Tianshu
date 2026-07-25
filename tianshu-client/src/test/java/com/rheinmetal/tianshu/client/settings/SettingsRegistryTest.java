package com.rheinmetal.tianshu.client.settings;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SettingsRegistryTest {
    @Test
    void presenceSettingsDoNotExposeInternalModuleSourceFilters() throws Exception {
        String source = Files.readString(
                Path.of("src/main/java/com/rheinmetal/tianshu/client/settings/module/presence/PresenceSettingsRegistrySource.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("presence.hud.sources"));
        assertFalse(source.contains("asrStatusVisible"));
        assertFalse(source.contains("llmStatusVisible"));
        assertFalse(source.contains("ttsStatusVisible"));
        assertFalse(source.contains("axStatusVisible"));
    }

    @Test
    void missingModuleDoesNotSilentlySelectAnotherCategory() {
        TianshuSettingsRegistry registry = new TianshuSettingsRegistry();
        registry.registerCategory(ModuleSettingsCategory.builder("module.one")
                .title(UiText.key("one"))
                .description(UiText.key("one.description"))
                .order(1)
                .panel((panel, context) -> {})
                .build());

        assertNull(registry.find("module.missing"));
    }

    @Test
    void oneContributorFailureDoesNotPreventOtherCategories() {
        AtomicReference<UiText> status = new AtomicReference<>();
        ModuleSettingsContext context = new ModuleSettingsContext() {
            @Override
            public SettingsCoordinator settingsCoordinator() {
                return new SettingsCoordinator();
            }

            @Override
            public void showStatus(UiText message, long durationMillis) {
                status.set(message);
            }
        };
        TianshuSettingsRegistry registry = new TianshuSettingsRegistry();

        CompositeSettingsRegistrySource.of(
                (ignored, ignoredContext) -> { throw new IllegalStateException("broken"); },
                (target, ignoredContext) -> target.registerCategory(ModuleSettingsCategory.builder("module.ok")
                        .title(UiText.key("ok"))
                        .description(UiText.key("ok.description"))
                        .order(1)
                        .panel((panel, panelContext) -> {})
                        .build())
        ).contribute(registry, context);

        assertEquals(1, registry.categories().size());
        assertEquals("module.ok", registry.categories().getFirst().moduleId());
        assertEquals(UiText.key("tianshu.gui.settings.status.module_unavailable", "broken"), status.get());
    }
}
