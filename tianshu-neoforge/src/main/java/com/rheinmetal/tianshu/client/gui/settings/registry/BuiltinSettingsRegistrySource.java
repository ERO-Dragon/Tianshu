package com.rheinmetal.tianshu.client.gui.settings.registry;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsPanel;
import com.rheinmetal.tianshu.client.gui.settings.api.SettingsButtonStyle;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSession;
import com.rheinmetal.tianshu.client.gui.settings.session.ModuleSettingsSessionBuilder;
import com.rheinmetal.tianshu.client.gui.settings.session.MutableSettingsValue;

import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.stream.IntStream;

public final class BuiltinSettingsRegistrySource implements TianshuSettingsRegistrySource {
    @Override
    public void contribute(TianshuSettingsRegistry registry, ModuleSettingsContext context) {
        registry.registerCategory(ModuleSettingsCategory.builder("system.settings")
                .title(Component.literal("系统"))
                .description(Component.literal("通用设置模板示例"))
                .order(0)
                .panel(this::buildPanel)
                .build());
    }

    private void buildPanel(ModuleSettingsPanel panel, ModuleSettingsContext context) {
        DemoSettingsValues values = new DemoSettingsValues();
        ModuleSettingsSession session = new ModuleSettingsSessionBuilder("system.settings")
                .values(values.enabled, values.toggleA, values.toggleB, values.mode, values.name, values.ratio)
                .successMessage(Component.literal("系统示例设置已保存"))
                .validationFailureMessage(Component.literal("系统示例设置存在无效值"))
                .build();
        context.settingsSessions().registerOrReplace(session);
        panel.text("intro", Component.literal("这里展示的是模块可复用的通用设置模板。"), TextBlockLevel.INFO)
                .enable("enabled", Component.literal("启用模块"), values.enabled)
                .toggles("toggles", Component.literal("开关组"), group -> group
                        .toggle("toggle.a", Component.literal("开关 A"), values.toggleA)
                        .toggle("toggle.b", Component.literal("开关 B"), values.toggleB)
                        .toggle("toggle.disabled", Component.literal("禁用开关"), () -> false, selected -> {}, () -> false))
                .options("options", Component.literal("选项组"), group -> group
                        .select("mode", Component.literal("模式"), List.of("默认", "紧凑", "详细"), values.mode, Component::literal)
                        .text("name", Component.literal("文本"), values.name)
                        .slider("ratio", Component.literal("比例"), values.ratio, 0.0D, 1.0D)
                        .slider("locked.ratio", Component.literal("禁用比例"), () -> 0.25D, 0.0D, 1.0D, value -> {}, () -> false))
                .status("status", Component.literal("状态组"), group -> group
                        .row("state", Component.literal("状态"), () -> Component.literal(session.dirty() ? "有未保存修改" : "可用"))
                        .row("source", Component.literal("来源"), () -> Component.literal("模块注册"))
                        .row("locked", Component.literal("禁用状态"), () -> Component.literal("不可编辑"), () -> false))
                .actions("actions", Component.literal("操作组"), group -> group
                        .button("refresh", Component.literal("刷新"), () -> context.showStatus(Component.literal("已刷新"), 3000))
                        .button("apply", Component.literal("应用"), SettingsButtonStyle.PRIMARY, () -> context.showStatus(context.settingsCoordinator().saveAll().message(), 3000))
                        .button("danger", Component.literal("危险"), SettingsButtonStyle.DANGER, () -> context.showStatus(Component.literal("已触发危险操作"), 3000))
                        .button("locked", Component.literal("禁用"), () -> {}, () -> false))
                .<String>list("list", Component.literal("列表组"), group -> group
                        .items(() -> IntStream.rangeClosed(1, 18).mapToObj(index -> "项目 " + index).toList())
                        .label(Component::literal)
                        .selected(() -> "项目 1")
                        .onSelect(value -> context.showStatus(Component.literal("已选择 " + value), 3000))
                        .emptyText(Component.literal("没有项目")));
    }

    private static final class DemoSettingsValues {
        private final MutableSettingsValue<Boolean> enabled = new MutableSettingsValue<>(() -> true, ignored -> {});
        private final MutableSettingsValue<Boolean> toggleA = new MutableSettingsValue<>(() -> true, ignored -> {});
        private final MutableSettingsValue<Boolean> toggleB = new MutableSettingsValue<>(() -> false, ignored -> {});
        private final MutableSettingsValue<String> mode = new MutableSettingsValue<>(() -> "默认", ignored -> {});
        private final MutableSettingsValue<String> name = new MutableSettingsValue<>(() -> "示例", ignored -> {});
        private final MutableSettingsValue<Double> ratio = new MutableSettingsValue<>(() -> 0.5D, ignored -> {}, value -> value != null && value >= 0.0D && value <= 1.0D);
    }
}
