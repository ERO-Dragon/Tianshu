package com.rheinmetal.tianshu.mc.ui.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorPoint;
import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorView;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import java.util.Objects;

/**
 * Full-screen, reversible editor for a normalized HUD anchor position.
 *
 * <p>It previews through the feature's own {@link HudAnchorView}, so the icon is resolved by the same layout the real
 * HUD uses and the preview cannot disagree with the element: dragging to a place the layout would clamp shows the
 * clamped result here too. The saved coordinate stays normalized, and the "not moved yet" case keeps the feature's
 * default anchor instead of freezing today's pixel position.</p>
 *
 * <p>It belongs to the platform because it is a screen, and a screen is platform UI: widgets, mouse capture, focus and
 * the transparent canvas are all game concerns. What the anchor means is the feature's.</p>
 */
public final class HudAnchorEditorScreen extends Screen {
    private static final int ICON_HIT_PADDING = 8;
    private static final int GRID_COLOR = 0x553F6470;
    private static final int CANVAS_COLOR = 0x660B1115;
    private static final int ICON_COLOR = 0xFF7DEBFF;

    private final Screen parent;
    private final HudAnchorView view;
    private final boolean initialDefaultAnchor;
    private double positionX;
    private double positionY;
    private boolean dragging;
    private boolean positionChanged;

    public HudAnchorEditorScreen(Screen parent, HudAnchorView view) {
        super(Component.translatable("tianshu.gui.hud.anchor_editor.title"));
        this.parent = parent;
        this.view = Objects.requireNonNull(view, "view");
        this.positionX = clamp(view.normalizedX(), 0.5D);
        double configuredY = view.normalizedY();
        this.initialDefaultAnchor = configuredY < 0.0D;
        this.positionY = initialDefaultAnchor ? -1.0D : clamp(configuredY, 0.5D);
    }

    @Override
    protected void init() {
        int buttonWidth = 92;
        int buttonHeight = 20;
        int gap = 8;
        int left = (width - buttonWidth * 2 - gap) / 2;
        int top = height - 36;
        addRenderableWidget(Button.builder(
                Component.translatable("tianshu.gui.settings.action.close"),
                button -> onClose()
        ).pos(left, top).size(buttonWidth, buttonHeight).build());
        addRenderableWidget(Button.builder(
                Component.translatable("tianshu.gui.settings.action.save"),
                button -> {
                    view.apply(positionX, savedPositionY());
                    returnToParent();
                }
        ).pos(left + buttonWidth + gap, top).size(buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        graphics.fill(0, 0, width, height, CANVAS_COLOR);
        drawGrid(graphics);
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("tianshu.gui.hud.anchor_editor.description"),
                width / 2, 22, 0xFFB9D5DE);

        HudAnchorPoint anchor = anchor();
        int halfSize = Math.max(1, Math.round(anchor.sizePixels() / 2.0F));
        int iconX = Math.round(anchor.centreX());
        int iconY = Math.round(anchor.centreY());
        graphics.fill(iconX - halfSize, iconY - halfSize, iconX + halfSize + 1, iconY + halfSize + 1, ICON_COLOR);
        // 1.21.9 renamed the outline draw to submitOutline when the GUI moved to submitted render states.
        graphics.submitOutline(iconX - halfSize - 2, iconY - halfSize - 2, halfSize * 2 + 5, halfSize * 2 + 5, 0xFFFFFFFF);
        for (GuiEventListener child : children()) {
            if (child instanceof Renderable renderable) {
                renderable.render(graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    /** 1.21.9 delivers one input record instead of loose coordinates and a button number. */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (super.mouseClicked(event, doubleClick)) {
            return true;
        }
        if (event.button() == 0 && isNearIcon(event.x(), event.y())) {
            dragging = true;
            updatePosition(event.x(), event.y());
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
        if (dragging && event.button() == 0) {
            updatePosition(event.x(), event.y());
            return true;
        }
        return super.mouseDragged(event, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() == 0 && dragging) {
            dragging = false;
            updatePosition(event.x(), event.y());
            return true;
        }
        return super.mouseReleased(event);
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    private void drawGrid(GuiGraphics graphics) {
        for (int column = 1; column < 4; column++) {
            int x = width * column / 4;
            graphics.fill(x, 36, x + 1, height - 44, GRID_COLOR);
        }
        for (int row = 1; row < 4; row++) {
            int y = 36 + (height - 80) * row / 4;
            graphics.fill(0, y, width, y + 1, GRID_COLOR);
        }
    }

    /** Exactly what the HUD would draw for the draft position: the feature's resolver, the feature's clamping. */
    private HudAnchorPoint anchor() {
        return view.resolve(positionX, positionY, width, height);
    }

    private void updatePosition(double mouseX, double mouseY) {
        positionX = clamp(mouseX / Math.max(1.0D, width), positionX);
        positionY = clamp(mouseY / Math.max(1.0D, height), positionY);
        positionChanged = true;
    }

    private boolean isNearIcon(double mouseX, double mouseY) {
        HudAnchorPoint anchor = anchor();
        double reach = anchor.sizePixels() / 2.0D + ICON_HIT_PADDING;
        double dx = mouseX - anchor.centreX();
        double dy = mouseY - anchor.centreY();
        return dx * dx + dy * dy <= reach * reach;
    }

    private double savedPositionY() {
        if (positionChanged || !initialDefaultAnchor) {
            return clamp(positionY, 0.5D);
        }
        return -1.0D;
    }

    private void returnToParent() {
        minecraft.setScreen(parent);
    }

    private static double clamp(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
