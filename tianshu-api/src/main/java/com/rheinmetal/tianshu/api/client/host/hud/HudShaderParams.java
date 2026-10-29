package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The product values one shader quad carries.
 *
 * <p>Deliberately a fixed set of primitives rather than a map: the programs share this uniform layout, so a call site
 * either has every value or it has a bug. The frame's time is not here — the platform injects it per quad — so a
 * settled state can reuse one instance without allocating.</p>
 *
 * @param intensity   overall energy of the visual, 0..1
 * @param speed       how fast the internal motion runs, 0..1 and beyond
 * @param convergence how tightly the visual's parts gather, 0..1
 * @param pulse       the listening pulse, 0..1
 * @param opacity     the layer's own alpha, 0..1
 * @param state       product state as the programs' agreed index
 * @param task        the parallel background-task mark, 0 or 1
 */
public record HudShaderParams(
        float intensity,
        float speed,
        float convergence,
        float pulse,
        float opacity,
        int state,
        float task
) {
    /** The index every program agrees on: the visual state enum's declaration order. */
    public static final int STATE_IDLE = 0;
    public static final int STATE_LOADING = 1;
    public static final int STATE_TASK = 2;
    public static final int STATE_CHAT = 3;
    public static final int STATE_CHAT_THINKING = 4;

    public HudShaderParams {
        intensity = clamp01(intensity);
        speed = Math.max(0.0F, speed);
        convergence = clamp01(convergence);
        pulse = clamp01(pulse);
        opacity = clamp01(opacity);
        state = Math.max(STATE_IDLE, Math.min(STATE_CHAT_THINKING, state));
        task = clamp01(task);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
