package com.rheinmetal.tianshu.neoforge.ui.settings;

import com.rheinmetal.tianshu.client.api.settings.layer.SettingsLayerModel;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * The window that shows one {@link SettingsLayerModel} over the settings page.
 *
 * <p>It is pushed onto NeoForge's GUI layer stack, which keeps the page below alive: that page is never
 * removed, keeps its widgets and its unsaved state, and is drawn by the stack itself. This screen
 * therefore draws only what belongs on top of it — a dim layer and the panel — and never the page again.
 * That is not a style choice: the stack renders the page first and this screen second, so drawing the
 * page here would paint its text above the panel.
 *
 * <p>Leaving is one path: {@link #onClose()} runs the layer's close hook, pops this layer off the stack
 * and asks the page underneath to rebuild, so a confirmed change becomes visible immediately. {@code Esc},
 * a closing button and the window being replaced all end here, and the close hook runs exactly once.
 */
public final class SettingsLayerScreen extends Screen {
    /** The page is on screen below; this only darkens it so the panel reads as the active surface. */
    private static final int DIM_COLOR = 0xA0101418;

    private final Screen parent;
    private final SettingsLayerModel layer;
    private final VanillaSettingsLayerRenderer renderer =
            new VanillaSettingsLayerRenderer(this::addLayerWidget, this::onClose);
    private boolean closed;

    public SettingsLayerScreen(Screen parent, SettingsLayerModel layer) {
        super(titleOf(layer));
        this.parent = parent;
        this.layer = Objects.requireNonNull(layer, "layer");
    }

    /** Lets the renderer hand its controls to this screen. */
    public void addLayerWidget(AbstractWidget widget) {
        addRenderableWidget(widget);
    }

    @Override
    protected void init() {
        closed = false;
        renderer.build(layer, font, width, height);
        if (renderer.initialFocus() != null) {
            setInitialFocus(renderer.initialFocus());
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderer.refresh(layer);
        // The page below is already drawn by the GUI stack at a lower depth; drawing it again here would
        // put its text above this panel, so this screen starts with the dim and the panel.
        graphics.fill(0, 0, width, height, DIM_COLOR);
        renderer.render(graphics, font, layer, mouseX, mouseY);
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (renderer.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && !renderer.contains(mouseX, mouseY)) {
            onClose();
        }
        return true;
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (renderer.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void onClose() {
        try {
            finishLayer();
        } finally {
            super.onClose();
            if (parent instanceof TianshuSettingsScreen settingsScreen) {
                settingsScreen.requestRebuildCurrentPage();
            }
        }
    }

    @Override
    public void removed() {
        try {
            finishLayer();
        } finally {
            super.removed();
        }
    }

    /** Runs the description's close hook once, whichever way the window goes away. */
    private void finishLayer() {
        if (closed) {
            return;
        }
        closed = true;
        layer.onClosed().run();
    }

    private static Component titleOf(SettingsLayerModel layer) {
        return NeoForgeUiText.toComponent(Objects.requireNonNull(layer, "layer").title());
    }
}
