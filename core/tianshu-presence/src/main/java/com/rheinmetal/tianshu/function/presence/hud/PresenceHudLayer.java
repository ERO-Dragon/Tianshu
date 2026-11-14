package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudFrameContext;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import com.rheinmetal.tianshu.api.client.host.hud.HudSurface;
import com.rheinmetal.tianshu.function.presence.partner.PresencePartnerSnapshot;
import com.rheinmetal.tianshu.function.presence.status.PresenceHudDisplay;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * The presence HUD as one platform layer: it composes the elements and hands each resolved frame to a renderer.
 *
 * <p>It is the presence half of the HUD. The platform half is the {@link HudSurface} it draws through, which means
 * this class holds every product decision — what is visible, what it says, where it sits, how it moves — and touches
 * no game type at all.</p>
 *
 * <p>The off switch is answered first and costs one boolean: a disabled HUD never builds a frame, never resolves a
 * layout and never allocates. That is the "HUD off costs nothing" rule expressed where the cost would otherwise
 * start.</p>
 */
public final class PresenceHudLayer implements HudLayer {
    public static final String LAYER_ID = "presence.hud";

    private final PresenceHudSettings settings;
    private final Supplier<PresenceHudDisplay> displaySupplier;
    private final Supplier<PresencePartnerSnapshot> partnerSupplier;
    private final PresenceStatusTextElementController statusTextController;
    private final PresenceIconElementController iconController;
    private final Map<PresenceHudElementType, PresenceHudElementRenderer> renderers;

    public PresenceHudLayer(
            PresenceHudSettings settings,
            Supplier<PresenceHudDisplay> displaySupplier,
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

    @Override
    public String id() {
        return LAYER_ID;
    }

    @Override
    public void render(HudFrameContext context, HudSurface surface) {
        if (context == null || surface == null) {
            return;
        }
        if (!settings.hudEnabled()) {
            clear();
            return;
        }
        PresenceHudElementUpdateContext update = new PresenceHudElementUpdateContext(
                displaySupplier.get(),
                context.renderMillis(),
                context.wallMillis(),
                surface.width(),
                surface.height(),
                partnerSupplier.get()
        );
        renderFrame(surface, iconController.update(update));
        renderFrame(surface, statusTextController.update(update));
    }

    /** Drops every per-element animation; the next frame starts as a fresh entrance. */
    public void clear() {
        statusTextController.reset();
        iconController.reset();
    }

    @Override
    public void reset() {
        clear();
    }

    /** The layout the icon would use on a screen of this size; the anchor editor previews through it. */
    public PresenceHudLayout layoutFor(int screenWidth, int screenHeight) {
        return iconController.layoutFor(screenWidth, screenHeight);
    }

    public PresenceHudSettings settings() {
        return settings;
    }

    private void renderFrame(HudSurface surface, Optional<PresenceHudElementFrame> frame) {
        if (frame.isEmpty()) {
            return;
        }
        PresenceHudElementFrame element = frame.get();
        PresenceHudElementRenderer renderer = renderers.get(element.type());
        if (renderer != null) {
            renderer.render(surface, element);
        }
    }
}
