package com.rheinmetal.tianshu.client.gui.settings.screen;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.layout.ScrollState;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsScreenChrome;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsScreenLayout;
import com.rheinmetal.tianshu.client.gui.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.gui.settings.model.ModuleSettingsPanelModel;
import com.rheinmetal.tianshu.client.gui.settings.registry.BuiltinSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.CompositeSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.gui.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.client.gui.settings.render.ModuleSettingsRenderer;
import com.rheinmetal.tianshu.client.gui.settings.render.ModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.client.gui.settings.render.SettingsRenderResult;
import com.rheinmetal.tianshu.client.gui.settings.render.VanillaModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class TianshuSettingsScreen extends Screen {
    private static final Component TITLE = Component.literal("天枢设置");
    private static final Component LEFT_TITLE = Component.literal("模块");
    private static final Component SAVE = Component.literal("保存");
    private static final int SCROLL_STEP = 24;

    private final ModuleSettingsContext context;
    private final TianshuSettingsRegistry registry;
    private final ModuleSettingsRendererProvider rendererProvider;
    private final SettingsScreenChrome chrome = new SettingsScreenChrome();
    private String selectedModuleId;
    private ScrollState rightPanelScroll = new ScrollState(0, 0, 0);

    public TianshuSettingsScreen(ModuleSettingsContext context, TianshuSettingsRegistry registry, ModuleSettingsRendererProvider rendererProvider) {
        super(TITLE);
        this.context = context;
        this.registry = registry;
        this.rendererProvider = rendererProvider;
    }

    public static TianshuSettingsScreen create(ModuleSettingsContext context, TianshuSettingsRegistrySource registrySource, ModuleSettingsRendererProvider rendererProvider) {
        TianshuSettingsRegistry registry = new TianshuSettingsRegistry();
        registrySource.contribute(registry, context);
        return new TianshuSettingsScreen(context, registry, rendererProvider);
    }

    public static TianshuSettingsScreen createDefault() {
        ModuleSettingsContext context = new TianshuSettingsContext();
        TianshuSettingsRegistrySource registrySource = CompositeSettingsRegistrySource.of(new BuiltinSettingsRegistrySource());
        return create(context, registrySource, new VanillaModuleSettingsRendererProvider());
    }

    @Override
    protected void init() {
        rebuildCurrentPage();
    }

    public void rebuildCurrentPage() {
        clearWidgets();
        List<ModuleSettingsCategory> categories = registry.categories();
        if (categories.isEmpty()) {
            return;
        }
        ModuleSettingsCategory selected = selectedModuleId == null ? categories.get(0) : registry.find(selectedModuleId);
        if (selected == null) {
            selected = categories.get(0);
        }
        selectedModuleId = selected.moduleId();

        SettingsScreenLayout layout = chrome.layout(width, height);

        addRenderableWidget(new SettingsNavigationWidget(layout.leftX(), layout.contentTop(), layout.leftWidth(), layout.panelHeight(), categories, selectedModuleId, category -> {
            selectedModuleId = category.moduleId();
            rightPanelScroll = rightPanelScroll.withOffset(0);
            rebuildCurrentPage();
        }, moduleId -> context.settingsCoordinator().dirty(moduleId)));

        ModuleSettingsPanelModel panel = new ModuleSettingsPanelModel();
        selected.panelFactory().build(panel, context);
        ModuleSettingsRenderer renderer = rendererProvider.createRenderer(context);
        SettingsViewport viewport = new SettingsViewport(layout.viewportTop(), layout.viewportBottom(), rightPanelScroll.offset());
        SettingsRenderResult result = renderer.render(this, font, layout.rightX() + 10, layout.viewportTop(), Math.max(1, layout.rightWidth() - 20), viewport, panel.templates());
        rightPanelScroll = rightPanelScroll.withMetrics(result.contentHeight(), viewport.height());

        addBottomActions(layout);
    }

    private void addBottomActions(SettingsScreenLayout layout) {
        int buttonWidth = 86;
        int buttonHeight = 20;
        int y = layout.actionsY();
        int x = layout.rightX() + (layout.rightWidth() - buttonWidth) / 2;

        Button saveButton = Button.builder(SAVE, button -> saveCurrent())
                .pos(x, y)
                .size(buttonWidth, buttonHeight)
                .build();
        saveButton.active = coordinator().canSave(selectedModuleId);
        addRenderableWidget(saveButton);
    }

    private SettingsCoordinator coordinator() {
        return context.settingsCoordinator();
    }

    private void saveCurrent() {
        SettingsSaveResult result = context.settingsCoordinator().save(selectedModuleId);
        showSaveResult(result);
        rebuildCurrentPage();
    }

    private void showSaveResult(SettingsSaveResult result) {
        if (result == null) {
            showStatus(Component.literal("设置操作没有返回结果"));
            return;
        }
        if (!result.success()) {
            showStatus(result.message().getString().isBlank() ? Component.literal("设置操作失败") : result.message());
            return;
        }
        if (result.requiresRestart()) {
            showStatus(Component.literal(result.message().getString() + "，需要重启游戏"));
        } else if (result.requiresReload()) {
            showStatus(Component.literal(result.message().getString() + "，需要重新加载运行时"));
        } else {
            showStatus(result.message().getString().isBlank() ? Component.literal("设置操作完成") : result.message());
        }
    }

    private void showStatus(Component message) {
        context.showStatus(message, 3000);
    }

    public void addSettingsWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        SettingsScreenLayout layout = chrome.layout(width, height);
        if (layout.containsRightPanel(mouseX, mouseY) && rightPanelScroll.canScroll()) {
            rightPanelScroll = rightPanelScroll.withOffset(rightPanelScroll.offset() - (int) Math.signum(scrollY) * SCROLL_STEP);
            rebuildCurrentPage();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        chrome.drawFrame(guiGraphics, chrome.layout(width, height), rightPanelScroll);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        chrome.drawForeground(guiGraphics, font, width, height, TITLE, LEFT_TITLE, selectedCategory(), statusMessage());
    }

    private ModuleSettingsCategory selectedCategory() {
        return selectedModuleId == null ? null : registry.find(selectedModuleId);
    }

    private Component statusMessage() {
        if (context instanceof TianshuSettingsContext settingsContext) {
            return settingsContext.statusMessage();
        }
        return Component.empty();
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().setScreen(null);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
