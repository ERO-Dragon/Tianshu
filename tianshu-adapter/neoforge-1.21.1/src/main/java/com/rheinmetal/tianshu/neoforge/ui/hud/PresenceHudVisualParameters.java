package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;

/** Immutable animation inputs shared by the shader and Java fallback renderer. */
public record PresenceHudVisualParameters(
        PresenceHudVisualState state,
        boolean listening,
        float intensity,
        float speed,
        float convergence,
        float pulse,
        float loadingBlend,
        PresenceHudLayout layout
) {
    public PresenceHudVisualParameters(
            PresenceHudVisualState state,
            boolean listening,
            float intensity,
            float speed,
            float convergence,
            float pulse,
            PresenceHudLayout layout
    ) {
        this(
                state,
                listening,
                intensity,
                speed,
                convergence,
                pulse,
                state == PresenceHudVisualState.LOADING ? 1.0F : 0.0F,
                layout
        );
    }

    public PresenceHudVisualParameters {
        state = state == null ? PresenceHudVisualState.IDLE : state;
        intensity = clamp01(intensity);
        speed = Math.max(0.0F, speed);
        convergence = clamp01(convergence);
        pulse = clamp01(pulse);
        loadingBlend = clamp01(loadingBlend);
        layout = layout == null ? PresenceHudLayout.resolve(null, 1, 1) : layout;
    }

    public static PresenceHudVisualParameters forState(
            PresenceHudVisualState state,
            boolean listening,
            PresenceHudLayout layout
    ) {
        PresenceHudVisualState effective = state == null ? PresenceHudVisualState.IDLE : state;
        float intensity;
        float speed;
        float convergence;
        switch (effective) {
            case LOADING -> {
                intensity = 0.70F;
                speed = 0.70F;
                convergence = 0.15F;
            }
            case TASK -> {
                intensity = 0.78F;
                speed = 0.90F;
                convergence = 0.35F;
            }
            case CHAT -> {
                intensity = 0.92F;
                speed = 1.15F;
                convergence = 0.10F;
            }
            case CHAT_THINKING -> {
                intensity = 0.66F;
                speed = 0.52F;
                convergence = 0.78F;
            }
            case IDLE -> {
                intensity = 0.34F;
                speed = 0.24F;
                convergence = 0.18F;
            }
            default -> throw new IllegalStateException("Unhandled visual state: " + effective);
        }
        float pulse = listening ? 1.0F : 0.0F;
        float loadingBlend = effective == PresenceHudVisualState.LOADING ? 1.0F : 0.0F;
        return new PresenceHudVisualParameters(
                effective,
                listening,
                intensity,
                speed,
                convergence,
                pulse,
                loadingBlend,
                layout
        );
    }

    public static PresenceHudVisualParameters interpolate(
            PresenceHudVisualParameters from,
            PresenceHudVisualParameters to,
            float progress
    ) {
        PresenceHudVisualParameters start = from == null ? to : from;
        PresenceHudVisualParameters end = to == null ? from : to;
        if (start == null || end == null) {
            return new PresenceHudVisualParameters(PresenceHudVisualState.IDLE, false, 0.0F, 0.0F, 0.0F, 0.0F, null);
        }
        float t = clamp01(progress);
        return new PresenceHudVisualParameters(
                end.state(),
                t < 0.5F ? start.listening() : end.listening(),
                lerp(start.intensity(), end.intensity(), t),
                lerp(start.speed(), end.speed(), t),
                lerp(start.convergence(), end.convergence(), t),
                lerp(start.pulse(), end.pulse(), t),
                lerp(start.loadingBlend(), end.loadingBlend(), t),
                end.layout()
        );
    }

    private static float lerp(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
