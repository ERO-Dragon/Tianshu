package com.rheinmetal.tianshu.client.settings.registry;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.api.settings.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.api.settings.TextBlockLevel;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.settings.session.ModuleSettingsSessionBuilder;
import com.rheinmetal.tianshu.client.settings.session.MutableSettingsValue;

import com.rheinmetal.tianshu.client.api.text.UiText;

import java.util.List;
import java.util.stream.IntStream;

public final class BuiltinSettingsRegistrySource implements TianshuSettingsRegistrySource {
    private static UiText demo(String key, Object... args) {
        return UiText.key("tianshu.gui.settings.demo." + key, args);
    }

    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        DemoSettingsValues values = new DemoSettingsValues();
        ModuleSettingsSession session = new ModuleSettingsSessionBuilder("system.settings")
                .values(values.enabled, values.toggleA, values.toggleB, values.mode, values.name, values.ratio)
                .successMessage(demo("message.saved"))
                .validationFailureMessage(demo("validation.invalid"))
                .build();
        context.settingsSessions().registerOrReplace(session);
        registry.registerCategory(ModuleSettingsCategory.builder("system.settings")
                .title(demo("title"))
                .description(demo("description"))
                .order(0)
                .panel((panel, panelContext) -> buildPanel(panel, panelContext, values, session))
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context, DemoSettingsValues values, ModuleSettingsSession session) {
        panel.text("intro", demo("intro"), TextBlockLevel.INFO)
                .enable("enabled", demo("enabled"), values.enabled)
                .toggles("toggles", demo("toggles"), group -> group
                        .toggle("toggle.a", demo("toggle.a"), values.toggleA)
                        .toggle("toggle.b", demo("toggle.b"), values.toggleB)
                        .toggle("toggle.disabled", demo("toggle.disabled"), () -> false, selected -> {}, () -> false))
                .options("options", demo("options"), group -> group
                        .select("mode", demo("mode"), List.of("default", "compact", "detailed"), values.mode, value -> demo("mode." + value))
                        .text("name", demo("text"), values.name)
                        .slider("ratio", demo("ratio"), values.ratio, 0.0D, 1.0D)
                        .slider("locked.ratio", demo("locked_ratio"), () -> 0.25D, 0.0D, 1.0D, value -> {}, () -> false))
                .status("status", demo("status"), group -> group
                        .row("state", demo("state"), () -> demo(session.dirty() ? "state.dirty" : "state.available"))
                        .row("source", demo("source"), () -> demo("source.module"))
                        .row("locked", demo("locked_state"), () -> demo("state.locked"), () -> false))
                .actions("actions", demo("actions"), group -> group
                        .button("refresh", demo("refresh"), () -> context.showStatus(demo("message.refreshed"), 3000))
                        .button("apply", demo("apply"), SettingsButtonStyle.PRIMARY, () -> context.showStatus(context.settingsCoordinator().saveAll().message(), 3000))
                        .button("danger", demo("danger"), SettingsButtonStyle.DANGER, () -> context.showStatus(demo("message.danger"), 3000))
                        .button("locked", demo("locked"), () -> {}, () -> false))
                .<String>list("list", demo("list"), group -> group
                        .items(() -> IntStream.rangeClosed(1, 18).mapToObj(index -> "item." + index).toList())
                        .label(value -> demo("item", value.substring("item.".length())))
                        .selected(() -> "item.1")
                        .onSelect(value -> context.showStatus(demo("message.selected", demo("item", value.substring("item.".length()))), 3000))
                        .emptyText(UiText.key("tianshu.gui.common.no_available_items")));
    }

    private static final class DemoSettingsValues {
        private final MutableSettingsValue<Boolean> enabled = new MutableSettingsValue<>(() -> true, ignored -> {});
        private final MutableSettingsValue<Boolean> toggleA = new MutableSettingsValue<>(() -> true, ignored -> {});
        private final MutableSettingsValue<Boolean> toggleB = new MutableSettingsValue<>(() -> false, ignored -> {});
        private final MutableSettingsValue<String> mode = new MutableSettingsValue<>(() -> "default", ignored -> {});
        private final MutableSettingsValue<String> name = new MutableSettingsValue<>(() -> "example", ignored -> {});
        private final MutableSettingsValue<Double> ratio = new MutableSettingsValue<>(() -> 0.5D, ignored -> {}, value -> value != null && value >= 0.0D && value <= 1.0D);
    }
}
