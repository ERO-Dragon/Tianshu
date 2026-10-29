package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudSettings;
import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.client.presence.status.PresenceHudDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

public final class PresenceHudRenderer {
    private final long renderClockOriginNanos = System.nanoTime();
    private final PresenceHudSettings settings;
    private final Supplier<PresenceHudDisplay> displaySupplier;
    private final Supplier<PresencePartnerSnapshot> partnerSupplier;
    private final PresenceStatusTextElementController statusTextController;
    private final PresenceIconElementController iconController;
    private final Map<PresenceHudElementType, PresenceHudElementRenderer> renderers;

    public PresenceHudRenderer(Supplier<PresenceHudDisplay> displaySupplier) {
        this(displaySupplier, PresenceHudSettings.ENABLED, () -> PresencePartnerSnapshot.HIDDEN);
    }

    public PresenceHudRenderer(Supplier<PresenceHudDisplay> displaySupplier, PresenceHudSettings settings) {
        this(displaySupplier, settings, () -> PresencePartnerSnapshot.HIDDEN);
    }

    /**
     * @param partnerSupplier the partner marker for this frame; it is asked against the wall clock, because a
     *                        lease deadline is a wall-clock instant rather than elapsed render time
     */
    public PresenceHudRenderer(
            Supplier<PresenceHudDisplay> displaySupplier,
            PresenceHudSettings settings,
            Supplier<PresencePartnerSnapshot> partnerSupplier
    ) {
        this.settings = settings == null ? PresenceHudSettings.ENABLED : settings;
        this.displaySupplier = Objects.requireNonNull(displaySupplier, "displaySupplier");
        this.partnerSupplier = partnerSupplier == null ? () -> PresencePartnerSnapshot.HIDDEN : partnerSupplier;
        this.statusTextController = new PresenceStatusTextElementController(this.settings);
        this.iconController = new PresenceIconElementController(this.settings);
        PresenceStatusTextElementRenderer statusTextRenderer = new PresenceStatusTextElementRenderer();
        PresenceIconElementRenderer iconRenderer = new PresenceIconElementRenderer(this.settings);
        this.renderers = Map.of(
                statusTextRenderer.type(), statusTextRenderer,
                iconRenderer.type(), iconRenderer
        );
    }

    public void render(GuiGraphics graphics) {
        if (graphics == null) {
            return;
        }
        if (!settings.hudEnabled()) {
            clear();
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }

        long nowMillis = renderClockMillis();
        PresenceHudElementUpdateContext context = new PresenceHudElementUpdateContext(
                displaySupplier.get(),
                nowMillis,
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight(),
                partnerSupplier.get()
        );
        Optional<PresenceHudElementFrame> iconFrame = iconController.update(context);
        Optional<PresenceHudElementFrame> statusTextFrame = statusTextController.update(context);
        Font font = minecraft.font;
        renderFrame(graphics, font, iconFrame);
        renderFrame(graphics, font, statusTextFrame);
    }

    public void clear() {
        statusTextController.reset();
        iconController.reset();
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

    /**
     * Render animation time is monotonic and advances whenever a render frame is sampled;
     * gameplay ticks are intentionally not part of this clock.
     */
    private long renderClockMillis() {
        long elapsedNanos = System.nanoTime() - renderClockOriginNanos;
        // Keep the first frame out of the timing record's "unset" sentinel path.
        return Math.max(1L, elapsedNanos / 1_000_000L);
    }
}
