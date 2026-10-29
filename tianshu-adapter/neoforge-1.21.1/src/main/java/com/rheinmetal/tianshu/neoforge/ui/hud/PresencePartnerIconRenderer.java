package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.math.Axis;
import com.rheinmetal.tianshu.client.presence.partner.PresencePartnerSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Set;

/**
 * Draws the partner marker: the icon of the module the player is (or would be) talking to, plus its lease ring.
 *
 * <p>The marker changes partner by cross-dissolving as a unit — the old icon and its ring fade out while the new
 * pair fades in, with no spin or slide. Changing partner is a change of subject, not an event that deserves an
 * entrance animation. The one motion that always runs is the rocking, which belongs to the marker itself.</p>
 *
 * <p>Coming from nothing and going back to nothing are the two gestures that do get a shape: the marker turns and
 * grows in, and turns and shrinks out, mirror images of each other. So the marker's life is bounded by the same
 * motion, and everything in between is a dissolve.</p>
 *
 * <p>Two transitions are carved out of that rule because they are the ring doing something on its own:</p>
 * <ul>
 *     <li>a lease <em>starts</em> (a winner appears, or a pre-winner on screen wins the turn) — the ring fills from
 *     empty to a full ring, then counts down;</li>
 *     <li>a lease is <em>renewed</em> or <em>handed over</em> — the ring fills from the length it was already
 *     showing back to a full ring, instead of restarting at zero.</li>
 * </ul>
 * <p>Every fill ends at full, so the only jump the ring ever makes is the one from "just filled" to the real
 * remaining length, a couple of degrees at most. While the lease runs down the ring follows it exactly.</p>
 *
 * <p>Everything here is local rendering. The only time-sensitive input is the lease deadline inside the
 * snapshot, compared against the wall clock — the same clock the arbiter used to stamp it — so the ring never
 * drifts from the icon's disappearance. Animation phases use the render clock instead, because they are about
 * smoothness, not about deadlines.</p>
 */
final class PresencePartnerIconRenderer {
    /** Fraction of the main icon's size the partner icon occupies. */
    private static final float ICON_SCALE = 0.55F;
    /**
     * How far the icon leans, in radians: it rocks around its own centre like a tumbler instead of spinning, so
     * the texture stays upright and readable at every point of the swing.
     */
    private static final float WOBBLE_AMPLITUDE_RADIANS = 0.26F;
    private static final float WOBBLE_PERIOD_MILLIS = 2_800.0F;
    private static final long CROSSFADE_MILLIS = 260L;
    /** How long the ring takes to grow to a longer lease; fast enough to read as one movement, not a fill bar. */
    private static final long RING_GROW_MILLIS = 220L;
    /** A renewal shorter than this is not a renewal; it keeps the ring from restating itself every frame. */
    private static final float RING_RENEW_THRESHOLD = 0.02F;
    private static final long EXIT_MILLIS = 320L;
    /** Scale the icon ends at while leaving; the spin and the fade share the same progress. */
    private static final float EXIT_MIN_SCALE = 0.55F;
    private static final float EXIT_SPIN_RADIANS = (float) Math.PI;
    /** The entrance mirrors the exit: the same distance, the same duration, spun the other way. */
    private static final long ENTER_MILLIS = EXIT_MILLIS;
    private static final float ENTER_MIN_SCALE = EXIT_MIN_SCALE;
    private static final float ENTER_SPIN_RADIANS = -EXIT_SPIN_RADIANS;
    private static final float RING_RADIUS_SCALE = 0.52F;
    private static final float RING_THICKNESS_SCALE = 0.05F;
    private static final int RING_COLOR = 0xFFEAF7FF;
    private static final float RING_OPACITY = 0.85F;
    private static final float START_ANGLE = (float) -Math.PI / 2.0F;

    private final Set<String> unavailableTextures = new HashSet<>();
    /** The partner on screen, refreshed in place while the same participant keeps the marker. */
    private PresencePartnerSnapshot shown;
    private long shownSinceMillis;
    /** The partner that is dissolving out. */
    private PresencePartnerSnapshot fading;
    private long fadingSinceMillis;
    private long hiddenSinceMillis;
    /** Whether the marker is playing its entrance; a change of partner is a dissolve instead. */
    private boolean entering;
    private long enterSinceMillis;
    /** Length of the shown ring currently drawn, {@code 0..1}; growth starts from here. */
    private float ringDrawn;
    private long ringGrowSince = -1L;
    private float ringGrowFrom;

    void render(GuiGraphics graphics, PresenceHudLayout layout, PresencePartnerSnapshot partner, long renderMillis) {
        if (graphics == null || layout == null) {
            return;
        }
        long wallNow = System.currentTimeMillis();
        if (partner == null || !partner.showIcon()) {
            exit(graphics, layout, renderMillis, wallNow);
            return;
        }
        // A partner arriving mid-animation must not rewind the copy that is leaving: it picks up from the alpha it
        // already has — whether that came from an exit or from an entrance that had not finished — instead of
        // restarting the dissolve at full.
        float outgoingAlpha = hiddenSinceMillis == 0L
                ? (entering ? Math.min(1.0F, (renderMillis - enterSinceMillis) / (float) ENTER_MILLIS) : 1.0F)
                : 1.0F - Math.min(1.0F, (renderMillis - hiddenSinceMillis) / (float) EXIT_MILLIS);
        hiddenSinceMillis = 0L;
        boolean ring = partner.showRingAt(wallNow);
        if (shown == null) {
            // Coming from nothing: the icon turns and grows in, and the ring fills from empty.
            shown = partner;
            shownSinceMillis = renderMillis;
            entering = true;
            enterSinceMillis = renderMillis;
            startRing(ring, 0.0F, renderMillis);
        } else if (!samePartner(shown, partner)) {
            // A change of subject: pure cross-dissolve, no entrance. Only the ring hands its length over, so a
            // winner replacing another winner keeps one continuous clock; a pre-winner has no length to hand over.
            float handedOver = shown.showRingAt(wallNow) ? ringDrawn : 0.0F;
            fading = shown;
            fadingSinceMillis = renderMillis - (long) ((1.0F - outgoingAlpha) * CROSSFADE_MILLIS);
            shown = partner;
            shownSinceMillis = renderMillis;
            entering = false;
            startRing(ring, handedOver, renderMillis);
        } else {
            float target = ringOf(partner, wallNow);
            boolean appearing = ring && ringDrawn <= 0.01F;
            boolean renewed = ring && ringDrawn > 0.01F && target > ringDrawn + RING_RENEW_THRESHOLD;
            if (appearing) {
                startRing(true, 0.0F, renderMillis);
            } else if (renewed) {
                startRing(true, ringDrawn, renderMillis);
            } else if (!ring) {
                startRing(false, 0.0F, renderMillis);
            }
            shown = partner;
        }

        float coverage = advanceRing(ring ? shown : null, renderMillis, wallNow);
        float enter = entering ? Math.min(1.0F, (renderMillis - enterSinceMillis) / (float) ENTER_MILLIS) : 1.0F;
        float alpha = entering ? enter : progress(renderMillis - shownSinceMillis);
        float scale = entering ? ENTER_MIN_SCALE + (1.0F - ENTER_MIN_SCALE) * enter : 1.0F;
        float spin = entering ? ENTER_SPIN_RADIANS * (1.0F - enter) : 0.0F;
        if (entering && enter >= 1.0F) {
            entering = false;
        }
        drawEntry(graphics, layout, shown, alpha, coverage, scale, spin, renderMillis, wallNow);
        if (fading != null) {
            float fadeOut = 1.0F - progress(renderMillis - fadingSinceMillis);
            if (fadeOut > 0.01F) {
                drawEntry(graphics, layout, fading, fadeOut, ringOf(fading, wallNow), 1.0F, 0.0F, renderMillis, wallNow);
            } else {
                fading = null;
            }
        }
    }

    /** The marker leaving: the icon turns, shrinks and fades, and the ring goes with it. */
    private void exit(GuiGraphics graphics, PresenceHudLayout layout, long renderMillis, long wallNow) {
        if (shown == null) {
            return;
        }
        if (hiddenSinceMillis == 0L) {
            hiddenSinceMillis = renderMillis;
        }
        float leaving = Math.min(1.0F, (renderMillis - hiddenSinceMillis) / (float) EXIT_MILLIS);
        if (leaving < 1.0F) {
            float alpha = 1.0F - leaving;
            float scale = 1.0F - (1.0F - EXIT_MIN_SCALE) * leaving;
            drawEntry(graphics, layout, shown, alpha, ringOf(shown, wallNow), scale,
                    EXIT_SPIN_RADIANS * leaving, renderMillis, wallNow);
            if (fading != null) {
                float fadeOut = Math.min(alpha, 1.0F - progress(renderMillis - fadingSinceMillis));
                if (fadeOut > 0.01F) {
                    drawEntry(graphics, layout, fading, fadeOut, ringOf(fading, wallNow), 1.0F, 0.0F, renderMillis, wallNow);
                }
            }
            return;
        }
        shown = null;
        shownSinceMillis = 0L;
        fading = null;
        fadingSinceMillis = 0L;
        hiddenSinceMillis = 0L;
        entering = false;
        startRing(false, 0.0F, renderMillis);
    }

    /**
     * One marker: ring first, then the icon on top.
     *
     * <p>{@code coverage} is the ring length to draw and is already the result of the growth animation;
     * {@code scale} and {@code spin} are only non-default while leaving.</p>
     */
    private void drawEntry(
            GuiGraphics graphics,
            PresenceHudLayout layout,
            PresencePartnerSnapshot entry,
            float alpha,
            float coverage,
            float scale,
            float spinRadians,
            long renderMillis,
            long wallNow
    ) {
        if (entry == null || alpha <= 0.01F) {
            return;
        }
        if (coverage > 0.001F && entry.showRingAt(wallNow)) {
            PresenceHudShapes.drawRing(
                    graphics,
                    layout.centerX(),
                    layout.centerY(),
                    layout.sizePixels() * RING_RADIUS_SCALE * scale,
                    Math.max(1.0F, layout.sizePixels() * RING_THICKNESS_SCALE),
                    RING_COLOR,
                    RING_OPACITY * alpha,
                    START_ANGLE,
                    coverage
            );
        }
        if (entry.showIcon()) {
            drawIcon(graphics, layout, entry.icon(), alpha, scale, spinRadians, renderMillis);
        }
    }

    private void drawIcon(
            GuiGraphics graphics,
            PresenceHudLayout layout,
            String icon,
            float alpha,
            float scale,
            float spinRadians,
            long renderMillis
    ) {
        if (alpha <= 0.01F || icon.isBlank()) {
            return;
        }
        ResourceLocation location = resolve(icon);
        if (location == null) {
            return;
        }
        int size = Math.max(4, Math.round(layout.sizePixels() * ICON_SCALE));
        float angle = wobbleRadians(renderMillis) + spinRadians;
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(layout.centerX(), layout.centerY(), 0.0F);
            graphics.pose().mulPose(Axis.ZP.rotation(angle));
            graphics.pose().scale(scale, scale, 1.0F);
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, Math.min(1.0F, alpha));
            graphics.blit(location, -size / 2, -size / 2, 0.0F, 0.0F, size, size, size, size);
        } catch (RuntimeException unavailable) {
            // A pack without the texture must degrade the marker, never the frame.
            unavailableTextures.add(icon);
        } finally {
            RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
            graphics.pose().popPose();
        }
    }

    /**
     * Resolves the icon once per path.
     *
     * <p>A missing texture is remembered: asking the resource manager every frame for a path that will never
     * exist is exactly the kind of per-frame I/O a HUD must not do.</p>
     */
    private ResourceLocation resolve(String icon) {
        if (unavailableTextures.contains(icon)) {
            return null;
        }
        ResourceLocation location = ResourceLocation.tryParse(icon);
        if (location == null) {
            unavailableTextures.add(icon);
            return null;
        }
        try {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft == null
                    || minecraft.getResourceManager() == null
                    || minecraft.getResourceManager().getResource(location).isEmpty()) {
                unavailableTextures.add(icon);
                return null;
            }
        } catch (RuntimeException unavailable) {
            unavailableTextures.add(icon);
            return null;
        }
        return location;
    }

    /** Whether the payload still describes the participant that is already on screen. */
    private static boolean samePartner(PresencePartnerSnapshot left, PresencePartnerSnapshot right) {
        return left.participantId().equals(right.participantId()) && left.icon().equals(right.icon());
    }

    /** Starts (or cancels) the ring's growth from {@code from}; a ring always grows, never jumps forward. */
    private void startRing(boolean hasRing, float from, long renderMillis) {
        if (!hasRing) {
            ringDrawn = 0.0F;
            ringGrowFrom = 0.0F;
            ringGrowSince = -1L;
            return;
        }
        ringDrawn = from;
        ringGrowFrom = from;
        ringGrowSince = renderMillis;
    }

    /**
     * The length to draw this frame: exact while counting down, growing towards a full ring while filling.
     *
     * <p>Every fill ends at a full ring — from empty when a lease starts, from wherever it was when the same or a
     * new winner takes over — and only then does the real countdown take over. Filling to the literal remaining
     * length instead would end the animation a hair short of full and read as "it never quite filled".</p>
     */
    private float advanceRing(PresencePartnerSnapshot entry, long renderMillis, long wallNow) {
        if (entry == null || !entry.showRingAt(wallNow)) {
            ringDrawn = 0.0F;
            ringGrowSince = -1L;
            return 0.0F;
        }
        float target = ringOf(entry, wallNow);
        if (ringGrowSince < 0L) {
            ringDrawn = target;
            return ringDrawn;
        }
        float grown = Math.max(0.0F, Math.min(1.0F, (renderMillis - ringGrowSince) / (float) RING_GROW_MILLIS));
        ringDrawn = ringGrowFrom + (1.0F - ringGrowFrom) * grown;
        if (grown >= 1.0F) {
            ringGrowSince = -1L;
            ringDrawn = target;
        }
        return ringDrawn;
    }

    private static float ringOf(PresencePartnerSnapshot entry, long wallNow) {
        return entry == null ? 0.0F : entry.remainingFraction(wallNow);
    }

    private static float progress(long elapsedMillis) {
        if (elapsedMillis <= 0L) {
            return 0.0F;
        }
        return Math.min(1.0F, elapsedMillis / (float) CROSSFADE_MILLIS);
    }

    /**
     * The tumbler swing: lean left, settle, lean right, settle.
     *
     * <p>A sine has zero velocity at both ends of the swing, which is what makes the motion read as a rocking
     * object rather than a rotating one — it decelerates into each extreme and accelerates through the middle.
     * The angle always returns to zero at the centre, so the icon is upright at rest and never accumulates
     * rotation.</p>
     */
    private static float wobbleRadians(long renderMillis) {
        float phase = (renderMillis % (long) WOBBLE_PERIOD_MILLIS) / WOBBLE_PERIOD_MILLIS;
        return WOBBLE_AMPLITUDE_RADIANS * (float) Math.sin(phase * Math.PI * 2.0);
    }
}
