package com.rheinmetal.tianshu.client.gui.settings.screen;

import com.rheinmetal.tianshu.client.gui.settings.api.ModuleSettingsContext;
import com.rheinmetal.tianshu.client.gui.settings.api.TextBlockLevel;
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
import com.rheinmetal.tianshu.client.gui.settings.render.SettingsDecoration;
import com.rheinmetal.tianshu.client.gui.settings.render.SettingsRenderResult;
import com.rheinmetal.tianshu.client.gui.settings.render.VanillaModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.gui.settings.session.SettingsSaveResult;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.List;

public final class TianshuSettingsScreen extends Screen {
    private static final Component TITLE = Component.translatable("tianshu.gui.settings.title");
    private static final Component LEFT_TITLE = Component.translatable("tianshu.gui.settings.modules");
    private static final Component SAVE = Component.translatable("tianshu.gui.settings.action.save");
    private static final Component CLOSE = Component.translatable("tianshu.gui.settings.action.close");
    private static final int SCROLL_STEP = 24;

    private final ModuleSettingsContext context;
    private final TianshuSettingsRegistry registry;
    private final ModuleSettingsRendererProvider rendererProvider;
    private final SettingsScreenChrome chrome = new SettingsScreenChrome();
    private String selectedModuleId;
    private ScrollState rightPanelScroll = new ScrollState(0, 0, 0);
    private List<SettingsDecoration> rightPanelDecorations = List.of();
    private Button saveButton;

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
        rightPanelDecorations = List.of();
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

        addRenderableWidget(new SettingsNavigationWidget(layout.leftX(), layout.leftListTop(), layout.leftWidth(), layout.leftListHeight(), categories, selectedModuleId, category -> {
            selectedModuleId = category.moduleId();
            rightPanelScroll = rightPanelScroll.withOffset(0);
            rebuildCurrentPage();
        }, moduleId -> context.settingsCoordinator().dirty(moduleId)));

        ModuleSettingsPanelModel panel = new ModuleSettingsPanelModel();
        buildPanel(selected, panel);
        ModuleSettingsRenderer renderer = rendererProvider.createRenderer(context);
        SettingsViewport viewport = new SettingsViewport(layout.viewportTop(), layout.viewportBottom(), rightPanelScroll.offset());
        SettingsRenderResult result = renderer.render(this, font, layout.rightX() + 6, layout.viewportTop() + 4, Math.max(1, layout.rightWidth() - 14), viewport, panel.templates());
        rightPanelScroll = rightPanelScroll.withMetrics(result.contentHeight(), viewport.height());
        rightPanelDecorations = result.decorations();

        addBottomActions(layout);
    }

    private void buildPanel(ModuleSettingsCategory selected, ModuleSettingsPanelModel panel) {
        try {
            selected.panelFactory().build(panel, context);
        } catch (IllegalStateException exception) {
            panel.text(
                    "settings.module.unavailable",
                    Component.translatable("tianshu.gui.settings.status.module_unavailable", exception.getMessage()),
                    TextBlockLevel.ERROR
            );
            context.showStatus(Component.translatable("tianshu.gui.settings.status.module_unavailable_short"), 4000);
        }
    }

    private void addBottomActions(SettingsScreenLayout layout) {
        int buttonWidth = 98;
        int buttonHeight = 20;
        int gap = 8;
        int y = layout.actionsY();
        int totalWidth = buttonWidth * 2 + gap;
        int x = (width - totalWidth) / 2;

        Button closeButton = Button.builder(CLOSE, button -> onClose())
                .pos(x, y)
                .size(buttonWidth, buttonHeight)
                .build();
        addRenderableWidget(closeButton);

        saveButton = Button.builder(SAVE, button -> saveAll())
                .pos(x + buttonWidth + gap, y)
                .size(buttonWidth, buttonHeight)
                .build();
        saveButton.active = coordinator().canSave();
        addRenderableWidget(saveButton);
    }

    private SettingsCoordinator coordinator() {
        return context.settingsCoordinator();
    }

    private void saveAll() {
        SettingsSaveResult result = context.settingsCoordinator().saveAll();
        showSaveResult(result);
        rebuildCurrentPage();
    }

    private void showSaveResult(SettingsSaveResult result) {
        if (result == null) {
            showStatus(Component.translatable("tianshu.gui.settings.status.no_result"));
            return;
        }
        if (!result.success()) {
            showStatus(result.message().getString().isBlank() ? Component.translatable("tianshu.gui.settings.status.failed") : result.message());
            return;
        }
        if (result.requiresRestart()) {
            showStatus(Component.translatable("tianshu.gui.settings.status.requires_restart", result.message()));
        } else if (result.requiresReload()) {
            showStatus(Component.translatable("tianshu.gui.settings.status.requires_reload", result.message()));
        } else {
            showStatus(result.message().getString().isBlank() ? Component.translatable("tianshu.gui.settings.status.completed") : result.message());
        }
    }

    private void showStatus(Component message) {
        context.showStatus(message, 3000);
    }

    public void addSettingsWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    public void showActionFailure(RuntimeException exception) {
        Component message = exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? Component.translatable("tianshu.gui.settings.status.action_failed")
                : Component.translatable("tianshu.gui.settings.status.action_failed_with_reason", exception.getMessage());
        context.showStatus(message, 4000);
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
        clearPointerFocus(mouseX, mouseY);
        updateActionStates();
        renderMenuBackground(guiGraphics);
        SettingsScreenLayout layout = chrome.layout(width, height);
        chrome.drawFrame(guiGraphics, layout, rightPanelScroll);
        drawRightPanelDecorationBackgrounds(guiGraphics, layout);
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        drawRightPanelDecorationBorders(guiGraphics, layout);
        chrome.drawForeground(guiGraphics, font, width, height, TITLE, LEFT_TITLE, selectedCategory(), statusMessage());
        chrome.drawOverlay(guiGraphics, layout, rightPanelScroll);
    }

    private void updateActionStates() {
        if (saveButton != null) {
            saveButton.active = coordinator().canSave();
        }
    }

    private void clearPointerFocus(int mouseX, int mouseY) {
        for (var child : children()) {
            if (child instanceof AbstractWidget widget && !(widget instanceof EditBox) && widget.isFocused() && !widget.isMouseOver(mouseX, mouseY)) {
                widget.setFocused(false);
            }
        }
    }

    private void drawRightPanelDecorationBackgrounds(GuiGraphics guiGraphics, SettingsScreenLayout layout) {
        guiGraphics.enableScissor(layout.rightX() + 1, layout.viewportTop(), layout.rightX() + layout.rightWidth() - 1, layout.viewportBottom());
        for (SettingsDecoration decoration : rightPanelDecorations) {
            decoration.drawBackground(guiGraphics);
        }
        guiGraphics.disableScissor();
    }

    private void drawRightPanelDecorationBorders(GuiGraphics guiGraphics, SettingsScreenLayout layout) {
        guiGraphics.enableScissor(layout.rightX() + 1, layout.viewportTop(), layout.rightX() + layout.rightWidth() - 1, layout.viewportBottom());
        for (SettingsDecoration decoration : rightPanelDecorations) {
            decoration.drawBorder(guiGraphics);
        }
        guiGraphics.disableScissor();
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
