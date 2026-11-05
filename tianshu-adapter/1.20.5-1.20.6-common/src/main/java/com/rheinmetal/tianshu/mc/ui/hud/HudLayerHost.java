package com.rheinmetal.tianshu.mc.ui.hud;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudAnchorEditor;
import com.rheinmetal.tianshu.api.client.host.hud.HudFrameContext;
import com.rheinmetal.tianshu.api.client.host.hud.HudHost;
import com.rheinmetal.tianshu.api.client.host.hud.HudLayer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The platform's HUD host: it renders whatever layers are registered and knows nothing about their content.
 *
 * <p>It owns the frame boundary — the game's "do not draw the HUD" answers, the two clocks, the surface — and the
 * failure policy: a layer that throws is counted and, after a few consecutive frames, dropped for the rest of the
 * session with one diagnostic. A feature bug therefore costs a HUD element, never the GUI.</p>
 *
 * <p>The layer list is re-sorted only when it changes, not every frame: the render loop must not allocate to ask
 * "what is registered".</p>
 */
public final class HudLayerHost implements HudHost {
    /** Consecutive failing frames after which a layer is dropped instead of retried forever. */
    private static final int MAX_CONSECUTIVE_FAILURES = 3;

    private final LogSink logSink;
    private final HudAnchorEditor anchorEditor;
    private final HudSurfaceRenderer surface = new HudSurfaceRenderer();
    private final Map<String, HudLayer> layers = new ConcurrentHashMap<>();
    private final Map<String, Integer> consecutiveFailures = new ConcurrentHashMap<>();
    private final long renderClockOriginNanos = System.nanoTime();
    private volatile List<HudLayer> orderedLayers = List.of();

    public HudLayerHost(LogSink logSink, HudAnchorEditor anchorEditor) {
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
        this.anchorEditor = Objects.requireNonNull(anchorEditor, "anchorEditor");
    }

    @Override
    public void addLayer(HudLayer layer) {
        if (layer == null || layer.id() == null || layer.id().isBlank()) {
            return;
        }
        layers.put(layer.id(), layer);
        consecutiveFailures.remove(layer.id());
        rebuildOrder();
    }

    @Override
    public void removeLayer(String layerId) {
        if (layerId == null || layers.remove(layerId) == null) {
            return;
        }
        consecutiveFailures.remove(layerId);
        rebuildOrder();
    }

    @Override
    public HudAnchorEditor anchorEditor() {
        return anchorEditor;
    }

    /** One frame: every registered layer, in order, with the platform's drawing surface. */
    public void render(GuiGraphics graphics) {
        List<HudLayer> active = orderedLayers;
        if (graphics == null || active.isEmpty()) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.options == null || minecraft.getWindow() == null) {
            return;
        }
        // The platform's two "not now" answers: the HUD is hidden (F1) or a screen is open.
        if (minecraft.options.hideGui || minecraft.screen != null) {
            return;
        }
        HudFrameContext frame = new HudFrameContext(
                renderMillis(),
                System.currentTimeMillis(),
                minecraft.getWindow().getGuiScaledWidth(),
                minecraft.getWindow().getGuiScaledHeight()
        );
        surface.bind(graphics, minecraft.font, frame);
        for (HudLayer layer : active) {
            try {
                layer.render(frame, surface);
                // Only touch the failure table when it has entries: the common frame must not write to a
                // concurrent map once per layer just to record "nothing went wrong".
                if (!consecutiveFailures.isEmpty()) {
                    consecutiveFailures.remove(layer.id());
                }
            } catch (RuntimeException failure) {
                onLayerFailure(layer, failure);
            }
        }
    }

    /** A resource or language reload invalidates measured text and resolved textures. */
    public void onResourcesReloaded() {
        surface.resetCaches();
    }

    /** The world ended: every layer drops its animation state before the next world's first frame. */
    public void resetLayers() {
        for (HudLayer layer : orderedLayers) {
            try {
                layer.reset();
            } catch (RuntimeException failure) {
                logSink.warn("neoforge.hud.layer_reset_failed id=" + layer.id());
            }
        }
    }

    private void onLayerFailure(HudLayer layer, RuntimeException failure) {
        int count = consecutiveFailures.merge(layer.id(), 1, Integer::sum);
        if (count >= MAX_CONSECUTIVE_FAILURES) {
            removeLayer(layer.id());
            logSink.error("neoforge.hud.layer_dropped id=" + layer.id(), failure);
            return;
        }
        logSink.warn("neoforge.hud.layer_failed id=" + layer.id() + " consecutive=" + count);
    }

    private void rebuildOrder() {
        orderedLayers = layers.values().stream()
                .sorted(Comparator.comparingInt(HudLayer::order))
                .toList();
    }

    /**
     * Render animation time is monotonic and advances whenever a render frame is sampled;
     * gameplay ticks are intentionally not part of this clock.
     */
    private long renderMillis() {
        long elapsedNanos = System.nanoTime() - renderClockOriginNanos;
        // Keep the first frame out of the timing record's "unset" sentinel path.
        return Math.max(1L, elapsedNanos / 1_000_000L);
    }
}
