package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;

import java.util.Objects;

/** Keeps visual state changes continuous within the selected rendering preset. */
public final class PresenceHudVisualTransition {
    private final long durationMillis;
    private PresenceHudVisualParameters from;
    private PresenceHudVisualParameters target;
    private long startedAtMillis;

    public PresenceHudVisualTransition(long durationMillis) {
        this.durationMillis = Math.max(1L, durationMillis);
    }

    public void reset() {
        from = null;
        target = null;
        startedAtMillis = 0L;
    }

    public void accept(
            PresenceHudVisualState state,
            boolean listening,
            long nowMillis,
            PresenceHudLayout layout
    ) {
        long now = Math.max(0L, nowMillis);
        PresenceHudVisualParameters next = PresenceHudVisualParameters.forState(
                state,
                listening,
                Objects.requireNonNull(layout, "layout")
        );
        if (target == null) {
            from = next;
            target = next;
            startedAtMillis = now;
            return;
        }
        if (target.state() == next.state() && target.listening() == next.listening()) {
            target = next;
            return;
        }
        from = sample(now, layout);
        target = next;
        startedAtMillis = now;
    }

    public PresenceHudVisualParameters sample(long nowMillis, PresenceHudLayout layout) {
        if (target == null) {
            return PresenceHudVisualParameters.forState(
                    PresenceHudVisualState.IDLE,
                    false,
                    Objects.requireNonNull(layout, "layout")
            );
        }
        float progress = Math.min(1.0F, Math.max(0.0F,
                (Math.max(0L, nowMillis) - startedAtMillis) / (float) durationMillis));
        float eased = smootherstep(progress);
        PresenceHudVisualParameters sampled = PresenceHudVisualParameters.interpolate(from, target, eased);
        if (progress >= 1.0F) {
            from = target;
        }
        return new PresenceHudVisualParameters(
                sampled.state(),
                sampled.listening(),
                sampled.intensity(),
                sampled.speed(),
                sampled.convergence(),
                sampled.pulse(),
                sampled.loadingBlend(),
                layout
        );
    }

    private static float smootherstep(float value) {
        return value * value * value * (value * (value * 6.0F - 15.0F) + 10.0F);
    }
}
