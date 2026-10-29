package com.rheinmetal.tianshu.client.presence.hud;

import java.util.Objects;

/**
 * Keeps visual state changes continuous within the selected rendering preset.
 *
 * <p>One instance per HUD element, sampled on every render frame. The settled state returns the same immutable
 * parameter object until something really changes, so a steady frame allocates nothing here; only the two
 * frames around a state change build new parameters. The layout is supplied on every {@link #accept} call
 * because a resize or a settings change must move the element even while the animation is settled.</p>
 */
public final class PresenceHudVisualTransition {
    private final long durationMillis;
    private PresenceHudVisualParameters from;
    private PresenceHudVisualParameters target;
    private PresenceHudLayout layout;
    private long startedAtMillis;

    public PresenceHudVisualTransition(long durationMillis) {
        this.durationMillis = Math.max(1L, durationMillis);
    }

    public void reset() {
        from = null;
        target = null;
        layout = null;
        startedAtMillis = 0L;
    }

    /**
     * Feeds the product state of this frame.
     *
     * <p>An unchanged state does not restart the transition, it only adopts the new layout: the window can be
     * resized and the icon moved while the animation is already settled.</p>
     */
    public void accept(
            PresenceHudVisualState state,
            boolean listening,
            long nowMillis,
            PresenceHudLayout layout
    ) {
        this.layout = Objects.requireNonNull(layout, "layout");
        long now = Math.max(0L, nowMillis);
        if (target == null) {
            from = PresenceHudVisualParameters.forState(state, listening, layout);
            target = from;
            startedAtMillis = now;
            return;
        }
        if (target.state() == state && target.listening() == listening) {
            if (target.layout() != layout) {
                from = withLayout(from, layout);
                target = withLayout(target, layout);
            }
            return;
        }
        from = sample(now);
        target = PresenceHudVisualParameters.forState(state, listening, layout);
        startedAtMillis = now;
    }

    /** The parameters of this frame; the same immutable instance while nothing is animating. */
    public PresenceHudVisualParameters sample(long nowMillis) {
        if (target == null) {
            return PresenceHudVisualParameters.forState(PresenceHudVisualState.IDLE, false, layout);
        }
        if (from == target) {
            return target;
        }
        float progress = Math.min(1.0F, Math.max(0.0F,
                (Math.max(0L, nowMillis) - startedAtMillis) / (float) durationMillis));
        if (progress >= 1.0F) {
            from = target;
            return target;
        }
        return PresenceHudVisualParameters.interpolate(from, target, smootherstep(progress));
    }

    private static PresenceHudVisualParameters withLayout(PresenceHudVisualParameters parameters, PresenceHudLayout layout) {
        if (parameters == null) {
            return null;
        }
        return new PresenceHudVisualParameters(
                parameters.state(),
                parameters.listening(),
                parameters.intensity(),
                parameters.speed(),
                parameters.convergence(),
                parameters.pulse(),
                parameters.loadingBlend(),
                layout
        );
    }

    private static float smootherstep(float value) {
        return value * value * value * (value * (value * 6.0F - 15.0F) + 10.0F);
    }
}
