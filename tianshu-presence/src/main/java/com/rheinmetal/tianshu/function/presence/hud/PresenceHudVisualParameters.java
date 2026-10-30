package com.rheinmetal.tianshu.function.presence.hud;

/**
 * Immutable animation inputs consumed by the icon's fragment programs.
 *
 * <p>{@code pulse} is the listening wave: it belongs to the visual state ({@link PresenceHudVisualState#LISTENING}
 * turns it on) instead of being a second flag carried beside every state. {@code loadingBlend} is the handoff
 * envelope: 0 is the settled shape, 1 is the loading cluster, and the icon collapses the outgoing layer into a
 * solid dot and grows the incoming layer out of it when the envelope crosses its midpoint.</p>
 */
public record PresenceHudVisualParameters(
        PresenceHudVisualState state,
        float intensity,
        float speed,
        float convergence,
        float pulse,
        float loadingBlend,
        PresenceHudLayout layout
) {
    public PresenceHudVisualParameters(
            PresenceHudVisualState state,
            float intensity,
            float speed,
            float convergence,
            float pulse,
            PresenceHudLayout layout
    ) {
        this(
                state,
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

    /**
     * The settled per-state look: idle is a small, quiet sphere; listening a larger shell with outside ripples;
     * thinking the reference aurora at the reference size; responding the listening aurora on a thinking-sized
     * soap bubble (the surface undulation itself is keyed off the shader state).
     */
    public static PresenceHudVisualParameters forState(
            PresenceHudVisualState state,
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
                intensity = 0.28F;
                speed = 0.90F;
                convergence = 0.34F;
            }
            case CHAT_THINKING -> {
                intensity = 0.66F;
                speed = 0.52F;
                convergence = 0.34F;
            }
            case IDLE -> {
                intensity = 0.24F;
                speed = 0.20F;
                convergence = 0.96F;
            }
            case LISTENING -> {
                intensity = 0.28F;
                speed = 0.24F;
                convergence = 0.15F;
            }
            default -> throw new IllegalStateException("Unhandled visual state: " + effective);
        }
        float pulse = effective == PresenceHudVisualState.LISTENING ? 1.0F : 0.0F;
        float loadingBlend = effective == PresenceHudVisualState.LOADING ? 1.0F : 0.0F;
        return new PresenceHudVisualParameters(
                effective,
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
            return new PresenceHudVisualParameters(PresenceHudVisualState.IDLE, 0.0F, 0.0F, 0.0F, 0.0F, null);
        }
        float t = clamp01(progress);
        return new PresenceHudVisualParameters(
                end.state(),
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
