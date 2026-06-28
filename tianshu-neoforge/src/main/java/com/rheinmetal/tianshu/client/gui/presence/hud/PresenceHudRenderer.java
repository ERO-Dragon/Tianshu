package com.rheinmetal.tianshu.client.gui.presence.hud;

import com.rheinmetal.tianshu.client.gui.presence.hud.element.PresenceHudElementFrame;
import com.rheinmetal.tianshu.client.gui.presence.hud.element.PresenceHudElementRenderer;
import com.rheinmetal.tianshu.client.gui.presence.hud.element.PresenceHudElementType;
import com.rheinmetal.tianshu.client.gui.presence.hud.element.PresenceStatusTextElementController;
import com.rheinmetal.tianshu.client.gui.presence.hud.element.PresenceStatusTextElementRenderer;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class PresenceHudRenderer {
    private final PresenceHudSettings settings;
    private final PresenceStatusTextElementController statusTextController;
    private final Map<PresenceHudElementType, PresenceHudElementRenderer> renderers;

    public PresenceHudRenderer(Supplier<PresenceHudDisplay> displaySupplier) {
        this(displaySupplier, PresenceHudSettings.ENABLED);
    }

    public PresenceHudRenderer(Supplier<PresenceHudDisplay> displaySupplier, PresenceHudSettings settings) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
        this.statusTextController = new PresenceStatusTextElementController(Objects.requireNonNull(displaySupplier, "displaySupplier"), this.settings);
        PresenceStatusTextElementRenderer statusTextRenderer = new PresenceStatusTextElementRenderer();
        this.renderers = Map.of(statusTextRenderer.type(), statusTextRenderer);
    }

    public void render(GuiGraphics graphics, float partialTick) {
        if (graphics == null || !settings.hudEnabled()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }

        Font font = minecraft.font;
        long nowMillis = System.currentTimeMillis();
        renderFrame(graphics, font, statusTextController.update(nowMillis));
    }

    private void renderFrame(GuiGraphics graphics, Font font, Optional<PresenceHudElementFrame> frame) {
        if (frame.isEmpty()) {
            return;
        }
        PresenceHudElementFrame element = frame.get();
        PresenceHudElementRenderer renderer = renderers.get(element.type());
        if (renderer != null) {
            renderer.render(graphics, font, element);
        }
    }
}
