package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/**
 * Full-screen, reversible editor for the normalized Presence icon position.
 *
 * <p>It draws the icon through the same {@link PresenceHudLayout} the HUD uses, with the draft position and the
 * saved size, so the preview cannot disagree with the real element: dragging to a place the layout would clamp
 * shows the clamped result here too. The saved coordinate stays normalized, and the "not moved yet" case keeps
 * the default experience-bar anchor instead of freezing today's pixel position.</p>
 */
public final class PresenceHudPositionEditorScreen extends Screen {
    private static final int ICON_HIT_PADDING = 8;
    private static final int GRID_COLOR = 0x553F6470;
    private static final int CANVAS_COLOR = 0x660B1115;
    private static final int ICON_COLOR = 0xFF7DEBFF;

    private final Screen parent;
    private final PresenceHudSettings settings;
    private final BiConsumer<Double, Double> applyPosition;
    private final boolean initialDefaultAnchor;
    private double positionX;
    private double positionY;
    private boolean dragging;
    private boolean positionChanged;

    public PresenceHudPositionEditorScreen(
            Screen parent,
            PresenceHudSettings settings,
            Supplier<Double> positionXSupplier,
            Supplier<Double> positionYSupplier,
            BiConsumer<Double, Double> applyPosition
    ) {
        super(Component.translatable("tianshu.gui.presence.position_editor.title"));
        this.parent = parent;
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
        Objects.requireNonNull(positionXSupplier, "positionXSupplier");
        Objects.requireNonNull(positionYSupplier, "positionYSupplier");
        this.applyPosition = Objects.requireNonNull(applyPosition, "applyPosition");
        this.positionX = clamp(positionXSupplier.get(), 0.5D);
        Double configuredY = positionYSupplier.get();
        this.initialDefaultAnchor = configuredY == null || !Double.isFinite(configuredY) || configuredY < 0.0D;
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
                    applyPosition.accept(positionX, savedPositionY());
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
                Component.translatable("tianshu.gui.presence.position_editor.description"),
                width / 2, 22, 0xFFB9D5DE);

        PresenceHudLayout layout = layout();
        int halfSize = Math.max(1, Math.round(layout.sizePixels() / 2.0F));
        int iconX = Math.round(layout.centerX());
        int iconY = Math.round(layout.centerY());
        graphics.fill(iconX - halfSize, iconY - halfSize, iconX + halfSize + 1, iconY + halfSize + 1, ICON_COLOR);
        graphics.renderOutline(iconX - halfSize - 2, iconY - halfSize - 2, halfSize * 2 + 5, halfSize * 2 + 5, 0xFFFFFFFF);
        for (Renderable renderable : renderables) {
            renderable.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        if (button == 0 && isNearIcon(mouseX, mouseY)) {
            dragging = true;
            updatePosition(mouseX, mouseY);
            return true;
        }
        return false;
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

    /** Exactly what the HUD would draw for the draft position: same resolver, same clamping. */
    private PresenceHudLayout layout() {
        return PresenceHudLayout.resolve(draftSettings(), width, height);
    }

    private PresenceHudSettings draftSettings() {
        return new PresenceHudSettings() {
            @Override
            public boolean hudEnabled() {
                return settings.hudEnabled();
            }

            @Override
            public boolean statusTextEnabled() {
                return settings.statusTextEnabled();
            }

            @Override
            public boolean iconEnabled() {
                return settings.iconEnabled();
            }

            @Override
            public double iconSizePixels() {
                return settings.iconSizePixels();
            }

            @Override
            public PresenceHudVisualPreset visualPreset() {
                return settings.visualPreset();
            }

            @Override
            public double iconPositionX() {
                return positionX;
            }

            @Override
            public double iconPositionY() {
                return positionY;
            }
        };
    }

    private void updatePosition(double mouseX, double mouseY) {
        positionX = clamp(mouseX / Math.max(1.0D, width), positionX);
        positionY = clamp(mouseY / Math.max(1.0D, height), positionY);
        positionChanged = true;
    }

    private boolean isNearIcon(double mouseX, double mouseY) {
        PresenceHudLayout layout = layout();
        double reach = layout.sizePixels() / 2.0D + ICON_HIT_PADDING;
        double dx = mouseX - layout.centerX();
        double dy = mouseY - layout.centerY();
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