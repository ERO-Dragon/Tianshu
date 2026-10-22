package com.rheinmetal.tianshu.neoforge.ui.hud;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Full-screen, reversible editor for the normalized Presence icon position. */
public final class PresenceHudPositionEditorScreen extends Screen {
    private static final int ICON_RADIUS = 14;
    private static final int GRID_COLOR = 0x553F6470;
    private static final int ICON_COLOR = 0xFF7DEBFF;

    private final Screen parent;
    private final BiConsumer<Double, Double> applyPosition;
    private final boolean initialDefaultAnchor;
    private double positionX;
    private double positionY;
    private boolean dragging;
    private boolean positionChanged;

    public PresenceHudPositionEditorScreen(
            Screen parent,
            Supplier<Double> positionXSupplier,
            Supplier<Double> positionYSupplier,
            BiConsumer<Double, Double> applyPosition
    ) {
        super(Component.translatable("tianshu.gui.presence.position_editor.title"));
        this.parent = parent;
        Objects.requireNonNull(positionXSupplier, "positionXSupplier");
        Objects.requireNonNull(positionYSupplier, "positionYSupplier");
        this.applyPosition = Objects.requireNonNull(applyPosition, "applyPosition");
        this.positionX = clamp(positionXSupplier.get(), 0.5D);
        Double configuredY = positionYSupplier.get();
        this.initialDefaultAnchor = configuredY == null || !Double.isFinite(configuredY) || configuredY < 0.0D;
        this.positionY = normalizedY(configuredY);
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
                    applyPosition.accept(positionX, positionChanged || !initialDefaultAnchor ? positionY : -1.0D);
                    returnToParent();
                }
        ).pos(left + buttonWidth + gap, top).size(buttonWidth, buttonHeight).build());
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderTransparentBackground(graphics);
        int canvasLeft = 16;
        int canvasTop = 28;
        int canvasRight = width - 16;
        int canvasBottom = height - 52;
        graphics.fill(canvasLeft, canvasTop, canvasRight, canvasBottom, 0xAA0B1115);
        for (int x = canvasLeft; x <= canvasRight; x += Math.max(1, (canvasRight - canvasLeft) / 8)) {
            graphics.fill(x, canvasTop, x + 1, canvasBottom, GRID_COLOR);
        }
        for (int y = canvasTop; y <= canvasBottom; y += Math.max(1, (canvasBottom - canvasTop) / 6)) {
            graphics.fill(canvasLeft, y, canvasRight, y + 1, GRID_COLOR);
        }
        graphics.drawCenteredString(font, title, width / 2, 8, 0xFFFFFFFF);
        graphics.drawCenteredString(font,
                Component.translatable("tianshu.gui.presence.position_editor.description"),
                width / 2, canvasTop + 8, 0xFFB9D5DE);

        int iconX = canvasX(canvasLeft, canvasRight, positionX);
        int iconY = canvasY(canvasTop, canvasBottom, positionY);
        graphics.fill(iconX - ICON_RADIUS, iconY - ICON_RADIUS, iconX + ICON_RADIUS + 1, iconY + ICON_RADIUS + 1, ICON_COLOR);
        graphics.renderOutline(iconX - ICON_RADIUS - 2, iconY - ICON_RADIUS - 2, ICON_RADIUS * 2 + 5, ICON_RADIUS * 2 + 5, 0xFFFFFFFF);
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isNearIcon(mouseX, mouseY)) {
            dragging = true;
            updatePosition(mouseX, mouseY);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (dragging && button == 0) {
            updatePosition(mouseX, mouseY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0 && dragging) {
            dragging = false;
            updatePosition(mouseX, mouseY);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    private void updatePosition(double mouseX, double mouseY) {
        int canvasLeft = 16;
        int canvasTop = 28;
        int canvasRight = width - 16;
        int canvasBottom = height - 52;
        positionX = clamp((mouseX - canvasLeft) / Math.max(1.0D, canvasRight - canvasLeft), positionX);
        positionY = clamp((mouseY - canvasTop) / Math.max(1.0D, canvasBottom - canvasTop), positionY);
        positionChanged = true;
    }

    private boolean isNearIcon(double mouseX, double mouseY) {
        int canvasLeft = 16;
        int canvasTop = 28;
        int canvasRight = width - 16;
        int canvasBottom = height - 52;
        double dx = mouseX - canvasX(canvasLeft, canvasRight, positionX);
        double dy = mouseY - canvasY(canvasTop, canvasBottom, positionY);
        return dx * dx + dy * dy <= (ICON_RADIUS + 8) * (ICON_RADIUS + 8);
    }

    private int canvasX(int left, int right, double normalized) {
        return left + (int) Math.round(clamp(normalized, 0.5D) * (right - left));
    }

    private int canvasY(int top, int bottom, double normalized) {
        return top + (int) Math.round(clamp(normalized, 0.82D) * (bottom - top));
    }

    private void returnToParent() {
        minecraft.setScreen(parent);
    }

    private static double normalizedY(Double value) {
        return value == null || !Double.isFinite(value) || value < 0.0D ? 0.82D : clamp(value, 0.82D);
    }

    private static double clamp(Double value, double fallback) {
        if (value == null || !Double.isFinite(value)) {
            return fallback;
        }
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}
