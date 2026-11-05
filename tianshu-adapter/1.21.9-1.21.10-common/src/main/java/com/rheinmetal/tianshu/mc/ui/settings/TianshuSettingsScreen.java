package com.rheinmetal.tianshu.mc.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.ModuleSettingsContext;
import com.rheinmetal.tianshu.mc.ui.settings.UiTexts;
import com.rheinmetal.tianshu.client.api.settings.TextBlockLevel;
import com.rheinmetal.tianshu.client.settings.layout.ScrollState;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsScreenChrome;
import com.rheinmetal.tianshu.client.settings.layout.SettingsScreenLayout;
import com.rheinmetal.tianshu.client.settings.layout.SettingsViewport;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsCategory;
import com.rheinmetal.tianshu.client.settings.model.ModuleSettingsPanelModel;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistry;
import com.rheinmetal.tianshu.client.settings.registry.TianshuSettingsRegistrySource;
import com.rheinmetal.tianshu.mc.ui.settings.ModuleSettingsRenderer;
import com.rheinmetal.tianshu.mc.ui.settings.ModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsDecoration;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsRenderResult;
import com.rheinmetal.tianshu.mc.ui.settings.SettingsScrollRegion;
import com.rheinmetal.tianshu.mc.ui.settings.VanillaModuleSettingsRendererProvider;
import com.rheinmetal.tianshu.client.settings.session.SettingsCoordinator;
import com.rheinmetal.tianshu.client.settings.session.SettingsSaveResult;
import com.rheinmetal.tianshu.client.settings.global.GlobalDebugSettingsSession;
import com.rheinmetal.tianshu.client.api.text.UiText;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

public final class TianshuSettingsScreen extends Screen {
    private static final Component TITLE = Component.translatable("tianshu.gui.settings.title");
    private static final Component LEFT_TITLE = Component.translatable("tianshu.gui.settings.modules");
    private static final Component SAVE = Component.translatable("tianshu.gui.settings.action.save");
    private static final Component CLOSE = Component.translatable("tianshu.gui.settings.action.close");
    private static final int SCROLL_STEP = 24;

    private final ModuleSettingsContext context;
    private final TianshuSettingsRegistry registry;
    private final ModuleSettingsRendererProvider rendererProvider;
    private final GlobalDebugSettingsSession debugSession;
    /** Where closing goes: the screen this one was opened over, or the game when it was opened from nothing. */
    private final Screen returnScreen;
    private final SettingsScreenChrome chrome = new SettingsScreenChrome();
    private String selectedModuleId;
    private ScrollState rightPanelScroll = new ScrollState(0, 0, 0);
    private List<SettingsDecoration> rightPanelDecorations = List.of();
    private List<SettingsScrollRegion> rightPanelScrollRegions = List.of();
    private final Map<String, ScrollState> nestedScrollStates = new ConcurrentHashMap<>();
    /** Frame-stable layout and per-region clip rectangles: both were rebuilt several times per frame. */
    private SettingsScreenLayout cachedLayout;
    private int cachedLayoutWidth = -1;
    private int cachedLayoutHeight = -1;
    private final Map<SettingsScrollRegion, VisibleRegion> visibleRegionCache = new java.util.IdentityHashMap<>();
    private Button saveButton;
    private SelectionPanel<?> selectionPanel;
    private boolean rebuildQueued;

    public TianshuSettingsScreen(ModuleSettingsContext context, TianshuSettingsRegistry registry, ModuleSettingsRendererProvider rendererProvider, GlobalDebugSettingsSession debugSession, Screen returnScreen) {
        super(TITLE);
        this.context = context;
        this.registry = registry;
        this.rendererProvider = rendererProvider;
        this.debugSession = debugSession;
        this.returnScreen = returnScreen;
    }

    public static TianshuSettingsScreen create(ModuleSettingsContext context, TianshuSettingsRegistrySource registrySource, ModuleSettingsRendererProvider rendererProvider, GlobalDebugSettingsSession debugSession, Screen returnScreen) {
        TianshuSettingsRegistry registry = new TianshuSettingsRegistry();
        registrySource.contribute(registry, context);
        return new TianshuSettingsScreen(context, registry, rendererProvider, debugSession, returnScreen);
    }

    @Override
    protected void init() {
        rebuildCurrentPage();
    }

    /**
     * The page layout for the current window size.
     *
     * <p>{@link #render} asks for it several times per frame — frame, decorations, widgets, overlay — and every
     * ask used to build a fresh {@link SettingsScreenLayout}. The size only changes on resize, so one cached
     * instance per size is enough; the per-region clip rectangles are derived from it and are dropped with it.</p>
     */
    private SettingsScreenLayout pageLayout() {
        if (cachedLayout == null || cachedLayoutWidth != width || cachedLayoutHeight != height) {
            cachedLayout = chrome.layout(width, height);
            cachedLayoutWidth = width;
            cachedLayoutHeight = height;
            visibleRegionCache.clear();
        }
        return cachedLayout;
    }

    public void rebuildCurrentPage() {
        rebuildQueued = false;
        clearWidgets();
        // The scroll regions below are new objects; clips cached against the old ones are unreachable.
        visibleRegionCache.clear();
        rightPanelDecorations = List.of();
        rightPanelScrollRegions = List.of();
        List<ModuleSettingsCategory> categories = registry.categories();
        SettingsScreenLayout layout = pageLayout();
        if (categories.isEmpty()) {
            // A kernel-only install has no module pages, but the screen still needs a way out: keep the footer
            // instead of returning with an empty page that can only be left with Esc.
            addBottomActions(layout);
            return;
        }
        ModuleSettingsCategory selected = selectedModuleId == null ? categories.get(0) : registry.find(selectedModuleId);
        if (selected == null) {
            selected = categories.get(0);
        }
        selectedModuleId = selected.moduleId();

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
        rightPanelScrollRegions = result.scrollRegions();
        pruneNestedScrollStates(rightPanelScrollRegions);

        addBottomActions(layout);
    }

    public void requestRebuildCurrentPage() {
        if (rebuildQueued) {
            return;
        }
        rebuildQueued = true;
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().screen == this) {
                rebuildCurrentPage();
            } else {
                rebuildQueued = false;
            }
        });
    }

    public void showExternalStatus(UiText message, long durationMillis) {
        context.showStatus(message, durationMillis);
    }

    private void buildPanel(ModuleSettingsCategory selected, ModuleSettingsPanelModel panel) {
        try {
            selected.panelFactory().build(panel, context);
        } catch (RuntimeException exception) {
            panel.text(
                    "settings.module.unavailable",
                    UiText.key("tianshu.gui.settings.status.module_unavailable", exception.getMessage()),
                    TextBlockLevel.ERROR
            );
            context.showStatus(UiText.key("tianshu.gui.settings.status.module_unavailable_short"), 4000);
        }
    }

    private void addBottomActions(SettingsScreenLayout layout) {
        int margin = 8;
        int debugWidth = debugSession == null ? 0 : 110;
        int buttonHeight = 20;
        int gap = 8;
        int y = layout.actionsY();
        int debugX = width - margin - debugWidth;
        int actionSpaceWidth = Math.max(1, debugX - gap - margin);
        int buttonWidth = Math.max(1, Math.min(98, (actionSpaceWidth - gap) / 2));
        int totalWidth = buttonWidth * 2 + gap;
        int x = margin + Math.max(0, (actionSpaceWidth - totalWidth) / 2);

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

        if (debugSession == null) {
            return;
        }
        Button debugButton = Button.builder(debugLabel(), button -> {
                    debugSession.toggle();
                    rebuildCurrentPage();
                })
                .pos(debugX, y)
                .size(debugWidth, buttonHeight)
                .build();
        addRenderableWidget(debugButton);
    }

    private Component debugLabel() {
        return Component.translatable(debugSession.enabled()
                ? "tianshu.gui.settings.debug.enabled"
                : "tianshu.gui.settings.debug.disabled");
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
            showStatus(UiText.key("tianshu.gui.settings.status.no_result"));
            return;
        }
        if (!result.success()) {
            showStatus(UiTexts.isEmpty(result.message()) ? UiText.key("tianshu.gui.settings.status.failed") : result.message());
            return;
        }
        if (result.requiresRestart()) {
            showStatus(UiText.key("tianshu.gui.settings.status.requires_restart", result.message()));
        } else if (result.requiresReload()) {
            showStatus(UiText.key("tianshu.gui.settings.status.requires_reload", result.message()));
        } else {
            showStatus(UiTexts.isEmpty(result.message()) ? UiText.key("tianshu.gui.settings.status.completed") : result.message());
        }
    }

    private void showStatus(UiText message) {
        context.showStatus(message, 3000);
    }

    public void addSettingsWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    public int scrollOffsetFor(String regionId) {
        if (regionId == null || regionId.isBlank()) {
            return 0;
        }
        ScrollState state = nestedScrollStates.get(regionId);
        return state == null ? 0 : state.offset();
    }

    public void registerScrollRegion(String regionId, int contentHeight, int viewportHeight) {
        if (regionId == null || regionId.isBlank()) {
            return;
        }
        nestedScrollStates.compute(regionId, (ignored, current) -> (current == null ? new ScrollState(0, contentHeight, viewportHeight) : current).withMetrics(contentHeight, viewportHeight));
    }

    public <T> void openSelectionPanel(UiText title, List<T> values, T selected, Function<T, UiText> labeler, Consumer<T> onSelect) {
        selectionPanel = new SelectionPanel<>(title, values, selected, labeler, value -> {
            if (onSelect != null) {
                onSelect.accept(value);
            }
            selectionPanel = null;
            rebuildCurrentPage();
        });
    }

    public void showActionFailure(RuntimeException exception) {
        UiText message = exception == null || exception.getMessage() == null || exception.getMessage().isBlank()
                ? UiText.key("tianshu.gui.settings.status.action_failed")
                : UiText.key("tianshu.gui.settings.status.action_failed_with_reason", exception.getMessage());
        context.showStatus(message, 4000);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (selectionPanel != null) {
            selectionPanel.mouseScrolled(scrollY);
            return true;
        }
        SettingsScreenLayout layout = pageLayout();
        for (SettingsScrollRegion region : rightPanelScrollRegions) {
            if (visibleRegionContains(layout, region, mouseX, mouseY) && region.canScroll()) {
                ScrollState current = nestedScrollStates.get(region.id());
                if (current == null) {
                    current = new ScrollState(0, region.contentHeight(), region.viewportHeight());
                }
                nestedScrollStates.put(region.id(), current.withMetrics(region.contentHeight(), region.viewportHeight())
                        .withOffset(current.offset() - (int) Math.signum(scrollY) * SCROLL_STEP));
                // Coalesced, not immediate: a high-resolution wheel delivers several events in one frame, and a
                // full page rebuild per notch was the one place this screen dropped frames.
                requestRebuildCurrentPage();
                return true;
            }
        }
        if (layout.containsRightPanel(mouseX, mouseY) && rightPanelScroll.canScroll()) {
            rightPanelScroll = rightPanelScroll.withOffset(rightPanelScroll.offset() - (int) Math.signum(scrollY) * SCROLL_STEP);
            requestRebuildCurrentPage();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int contentMouseX = selectionPanel == null ? mouseX : Integer.MIN_VALUE;
        int contentMouseY = selectionPanel == null ? mouseY : Integer.MIN_VALUE;
        clearPointerFocus(contentMouseX, contentMouseY);
        updateActionStates();
        renderMenuBackground(guiGraphics);
        SettingsScreenLayout layout = pageLayout();
        chrome.drawFrame(guiGraphics, layout, rightPanelScroll);
        drawRightPanelDecorationBackgrounds(guiGraphics, layout);
        super.render(guiGraphics, contentMouseX, contentMouseY, partialTick);
        renderScrollRegionWidgets(guiGraphics, contentMouseX, contentMouseY, partialTick);
        drawRightPanelDecorationBorders(guiGraphics, layout);
        chrome.drawForeground(guiGraphics, font, width, height, TITLE, LEFT_TITLE, selectedCategory(), statusMessage());
        if (registry.categories().isEmpty()) {
            drawEmptyState(guiGraphics, layout);
        }
        chrome.drawOverlay(guiGraphics, layout, rightPanelScroll);
        if (selectionPanel != null) {
            selectionPanel.render(guiGraphics, font, width, height, mouseX, mouseY);
        }
    }

    /**
     * 1.21.9 delivers one input record instead of loose coordinates, so the pointer is read off the event and the
     * region widgets are handed that same event: it already carries the cursor position they hit-test against.
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (selectionPanel != null && selectionPanel.mouseClicked(event.x(), event.y())) {
            return true;
        }
        SettingsScreenLayout layout = pageLayout();
        for (SettingsScrollRegion region : rightPanelScrollRegions) {
            if (!visibleRegionContains(layout, region, event.x(), event.y())) {
                continue;
            }
            for (AbstractWidget widget : region.widgets()) {
                if (widget.mouseClicked(event, doubleClick)) {
                    return true;
                }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (selectionPanel != null) {
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (selectionPanel != null) {
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean keyPressed(KeyEvent event) {
        if (selectionPanel != null) {
            if (event.isEscape()) {
                selectionPanel = null;
            }
            return true;
        }
        return super.keyPressed(event);
    }

    @Override
    public boolean charTyped(CharacterEvent event) {
        if (selectionPanel != null) {
            return true;
        }
        return super.charTyped(event);
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
        try {
            for (SettingsDecoration decoration : rightPanelDecorations) {
                decoration.drawBackground(guiGraphics);
            }
        } finally {
            guiGraphics.disableScissor();
        }
        for (SettingsScrollRegion region : rightPanelScrollRegions) {
            drawScrollRegionDecorations(guiGraphics, region, true);
        }
    }

    private void drawRightPanelDecorationBorders(GuiGraphics guiGraphics, SettingsScreenLayout layout) {
        guiGraphics.enableScissor(layout.rightX() + 1, layout.viewportTop(), layout.rightX() + layout.rightWidth() - 1, layout.viewportBottom());
        try {
            for (SettingsDecoration decoration : rightPanelDecorations) {
                decoration.drawBorder(guiGraphics);
            }
        } finally {
            guiGraphics.disableScissor();
        }
        for (SettingsScrollRegion region : rightPanelScrollRegions) {
            drawScrollRegionDecorations(guiGraphics, region, false);
            drawScrollRegionScrollbar(guiGraphics, region);
        }
    }

    private void renderScrollRegionWidgets(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        SettingsScreenLayout layout = pageLayout();
        for (SettingsScrollRegion region : rightPanelScrollRegions) {
            VisibleRegion visible = visibleRegion(layout, region);
            if (visible == null) {
                continue;
            }
            guiGraphics.enableScissor(visible.left(), visible.top(), visible.right(), visible.bottom());
            try {
                for (AbstractWidget widget : region.widgets()) {
                    widget.render(guiGraphics, mouseX, mouseY, partialTick);
                }
            } finally {
                guiGraphics.disableScissor();
            }
        }
    }

    private void drawScrollRegionDecorations(GuiGraphics guiGraphics, SettingsScrollRegion region, boolean background) {
        SettingsScreenLayout layout = pageLayout();
        VisibleRegion visible = visibleRegion(layout, region);
        if (visible == null) {
            return;
        }
        guiGraphics.enableScissor(visible.left(), visible.top(), visible.right(), visible.bottom());
        try {
            for (SettingsDecoration decoration : region.decorations()) {
                if (background) {
                    decoration.drawBackground(guiGraphics);
                } else {
                    decoration.drawBorder(guiGraphics);
                }
            }
        } finally {
            guiGraphics.disableScissor();
        }
    }

    private void drawScrollRegionScrollbar(GuiGraphics guiGraphics, SettingsScrollRegion region) {
        if (!region.canScroll() || region.viewportHeight() <= 0) {
            return;
        }
        SettingsScreenLayout layout = pageLayout();
        VisibleRegion visible = visibleRegion(layout, region);
        if (visible == null) {
            return;
        }
        ScrollState scroll = nestedScrollStates.get(region.id());
        int offset = scroll == null ? 0 : scroll.offset();
        int contentHeight = Math.max(1, region.contentHeight());
        int viewportHeight = region.viewportHeight();
        int maxOffset = Math.max(1, contentHeight - viewportHeight);
        int scrollbarX = visible.right() - 4;
        int trackTop = visible.top();
        int trackBottom = visible.bottom();
        int trackHeight = Math.max(1, trackBottom - trackTop);
        int thumbHeight = Math.max(16, trackHeight * viewportHeight / contentHeight);
        int thumbY = trackTop + (trackHeight - thumbHeight) * offset / maxOffset;
        guiGraphics.fill(scrollbarX, trackTop, scrollbarX + 2, trackBottom, 0x66000000);
        guiGraphics.fill(scrollbarX, thumbY, scrollbarX + 2, thumbY + thumbHeight, 0xFFB0B0B0);
    }

    private boolean visibleRegionContains(SettingsScreenLayout layout, SettingsScrollRegion region, double mouseX, double mouseY) {
        VisibleRegion visible = visibleRegion(layout, region);
        return visible != null && visible.contains(mouseX, mouseY);
    }

    /**
     * The on-screen clip of one scroll region, memoised for the frame.
     *
     * <p>The same region is clipped three times per frame (background, border, widgets) and used again for hit
     * testing; the cache is keyed to the region instance and dropped whenever the layout or the regions change.</p>
     */
    private VisibleRegion visibleRegion(SettingsScreenLayout layout, SettingsScrollRegion region) {
        VisibleRegion cached = visibleRegionCache.get(region);
        if (cached != null) {
            return cached;
        }
        int left = Math.max(region.x(), layout.rightX() + 1);
        int right = Math.min(region.x() + region.width(), layout.rightX() + layout.rightWidth() - 1);
        int top = Math.max(region.y(), layout.viewportTop());
        int bottom = Math.min(region.y() + region.height(), layout.viewportBottom());
        if (right <= left || bottom <= top) {
            return null;
        }
        VisibleRegion computed = new VisibleRegion(left, top, right, bottom);
        visibleRegionCache.put(region, computed);
        return computed;
    }

    private void pruneNestedScrollStates(List<SettingsScrollRegion> regions) {
        Set<String> activeIds = new HashSet<>();
        for (SettingsScrollRegion region : regions) {
            activeIds.add(region.id());
        }
        nestedScrollStates.keySet().removeIf(id -> !activeIds.contains(id));
    }

    private ModuleSettingsCategory selectedCategory() {
        return selectedModuleId == null ? null : registry.find(selectedModuleId);
    }

    /**
     * A kernel-only install has no module pages. Without a word here the screen is an empty frame with three
     * buttons, which reads as a broken page rather than "nothing is installed".
     */
    private void drawEmptyState(GuiGraphics guiGraphics, SettingsScreenLayout layout) {
        Component title = Component.translatable("tianshu.gui.settings.empty.title");
        int centreX = (layout.leftX() + layout.rightX() + layout.rightWidth()) / 2;
        int centreY = (layout.contentTop() + layout.actionsY()) / 2;
        guiGraphics.drawCenteredString(font, title, centreX, centreY - 4, 0xFFFFFF);
    }

    private Component statusMessage() {
        if (context instanceof TianshuSettingsContext settingsContext) {
            return UiTexts.toComponent(settingsContext.statusMessage());
        }
        return Component.empty();
    }

    /**
     * The player left the page: release the draft sessions here.
     *
     * <p>Leaving with unsaved drafts asks first, because the alternative is losing a session's worth of edits
     * to a stray Esc. The prompt is the vanilla {@link ConfirmScreen}; dismissing it (Esc there) returns here
     * rather than dropping the page along with its drafts.</p>
     */
    @Override
    public void onClose() {
        if (!coordinator().canSave()) {
            closeAndRelease();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.setScreen(new DiscardDraftConfirmScreen(
                discard -> {
                    if (discard) {
                        closeAndRelease();
                    } else {
                        minecraft.setScreen(this);
                    }
                },
                Component.translatable("tianshu.gui.settings.confirm.discard.title"),
                Component.translatable("tianshu.gui.settings.confirm.discard.message"),
                Component.translatable("tianshu.gui.settings.confirm.discard.leave"),
                Component.translatable("tianshu.gui.settings.confirm.discard.stay"),
                () -> minecraft.setScreen(this)
        ));
    }

    private void closeAndRelease() {
        context.settingsSessions().close();
        Minecraft.getInstance().setScreen(returnScreen);
    }

    /** A confirmation whose Esc means "go back to the page", not "dismiss the page with its drafts". */
    private static final class DiscardDraftConfirmScreen extends ConfirmScreen {
        private final Runnable onDismiss;

        private DiscardDraftConfirmScreen(
                it.unimi.dsi.fastutil.booleans.BooleanConsumer callback,
                Component title,
                Component message,
                Component leave,
                Component stay,
                Runnable onDismiss
        ) {
            super(callback, title, message, leave, stay);
            this.onDismiss = onDismiss;
        }

        @Override
        public void onClose() {
            onDismiss.run();
        }
    }

    @Override
    public boolean isPauseScreen() {
        return true;
    }

    private record VisibleRegion(int left, int top, int right, int bottom) {
        private boolean contains(double mouseX, double mouseY) {
            return mouseX >= left && mouseX <= right && mouseY >= top && mouseY <= bottom;
        }
    }

    private final class SelectionPanel<T> {
        private static final int ENTRY_HEIGHT = 18;
        private static final int PADDING = 6;
        private static final int MAX_VISIBLE = 10;
        private static final int WIDTH = 190;
        private static final int BACKGROUND = 0xF0101010;
        private static final int BORDER = 0xFF808080;
        private static final int HOVER_BG = 0x40FFFFFF;
        private static final int SELECTED_COLOR = 0xFFFFA0;
        private static final int NORMAL_COLOR = 0xE0E0E0;

        private final UiText title;
        private final List<T> values;
        private final T selected;
        private final Function<T, UiText> labeler;
        private final Consumer<T> onSelect;
        private int scrollOffset;

        private SelectionPanel(UiText title, List<T> values, T selected, Function<T, UiText> labeler, Consumer<T> onSelect) {
            this.title = title == null ? UiText.literal("") : title;
            this.values = values == null ? List.of() : List.copyOf(values);
            this.selected = selected;
            this.labeler = labeler == null ? value -> UiText.literal(String.valueOf(value)) : labeler;
            this.onSelect = onSelect == null ? value -> {} : onSelect;
        }

        private void render(GuiGraphics guiGraphics, Font font, int screenWidth, int screenHeight, int mouseX, int mouseY) {
            int visibleCount = Math.min(MAX_VISIBLE, values.size());
            int titleHeight = font.lineHeight + PADDING * 2;
            int height = titleHeight + visibleCount * ENTRY_HEIGHT + PADDING;
            int x = (screenWidth - WIDTH) / 2;
            int y = (screenHeight - height) / 2;
            // The panel is submitted last in this screen's render, and 1.21.6 layers GUI content by submission
            // order; the old stack's z offset that raised it above the screen has no 2D equivalent and is gone.
            guiGraphics.fill(0, 0, screenWidth, screenHeight, 0x66000000);
            guiGraphics.fill(x, y, x + WIDTH, y + height, BACKGROUND);
            // 1.21.9 renamed the outline draw to submitOutline when the GUI moved to submitted render states.
            guiGraphics.submitOutline(x, y, WIDTH, height, BORDER);
            guiGraphics.drawCenteredString(font, UiTexts.toComponent(title), screenWidth / 2, y + PADDING, 0xFFFFFF);
            int entryY = y + titleHeight;
            clampScroll();
            guiGraphics.enableScissor(x + 1, entryY, x + WIDTH - 1, y + height - 1);
            try {
                for (int i = 0; i < visibleCount; i++) {
                    int idx = i + scrollOffset;
                    if (idx >= values.size()) {
                        break;
                    }
                    T value = values.get(idx);
                    Component label = UiTexts.toComponent(labeler.apply(value));
                    boolean selectedValue = value != null && value.equals(selected);
                    int rowY = entryY + i * ENTRY_HEIGHT;
                    boolean hovered = mouseX >= x && mouseX < x + WIDTH && mouseY >= rowY && mouseY < rowY + ENTRY_HEIGHT;
                    if (hovered) {
                        guiGraphics.fill(x + 1, rowY, x + WIDTH - 1, rowY + ENTRY_HEIGHT, HOVER_BG);
                    }
                    guiGraphics.drawString(font, label, x + PADDING + 4, rowY + (ENTRY_HEIGHT - font.lineHeight) / 2, selectedValue ? SELECTED_COLOR : NORMAL_COLOR, false);
                }
            } finally {
                guiGraphics.disableScissor();
            }
        }

        private boolean mouseClicked(double mouseX, double mouseY) {
            Font font = Minecraft.getInstance().font;
            int visibleCount = Math.min(MAX_VISIBLE, values.size());
            int titleHeight = font.lineHeight + PADDING * 2;
            int height = titleHeight + visibleCount * ENTRY_HEIGHT + PADDING;
            int x = (TianshuSettingsScreen.this.width - WIDTH) / 2;
            int y = (TianshuSettingsScreen.this.height - height) / 2;
            int entryY = y + titleHeight;
            if (mouseX >= x && mouseX < x + WIDTH && mouseY >= entryY && mouseY < y + height) {
                int idx = (int) ((mouseY - entryY) / ENTRY_HEIGHT) + scrollOffset;
                if (idx >= 0 && idx < values.size()) {
                    onSelect.accept(values.get(idx));
                }
                return true;
            }
            if (mouseX < x || mouseX >= x + WIDTH || mouseY < y || mouseY >= y + height) {
                selectionPanel = null;
                return true;
            }
            return true;
        }

        private void mouseScrolled(double scrollY) {
            scrollOffset -= (int) Math.signum(scrollY);
            clampScroll();
        }

        private void clampScroll() {
            int maxScroll = Math.max(0, values.size() - MAX_VISIBLE);
            scrollOffset = Math.max(0, Math.min(scrollOffset, maxScroll));
        }
    }
}
