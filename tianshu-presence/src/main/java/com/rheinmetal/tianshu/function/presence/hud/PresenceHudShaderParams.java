package com.rheinmetal.tianshu.function.presence.hud;

/**
 * The values presence writes into its own HUD program.
 *
 * <p>This type lives in presence, not in the api. Adding a visual control means adding a field here, teaching
 * {@link PresenceHudProgram} to write its uniform and updating the shader asset; the released platform does not need a
 * new parameter layout. The shader receives floats only, so the state index is carried as a float.</p>
 *
 * @param intensity   overall energy of the visual, 0..1
 * @param speed       how fast the internal motion runs, 0..1 and beyond
 * @param convergence how tightly the visual's parts gather, 0..1
 * @param pulse       the listening pulse, 0..1
 * @param opacity     the layer's own alpha, 0..1
 * @param morph       0 = the settled shape, 1 = the loading cluster
 * @param state       presence's visual-state index understood by the shaders
 * @param task        the parallel background-task mark, 0 or 1
 */
public record PresenceHudShaderParams(
        float intensity,
        float speed,
        float convergence,
        float pulse,
        float opacity,
        float morph,
        int state,
        float task
) {
    /** The shader's state index; it mirrors {@link PresenceHudVisualState}'s declaration order. */
    static final int STATE_IDLE = 0;
    static final int STATE_LOADING = 1;
    static final int STATE_TASK = 2;
    static final int STATE_CHAT = 3;
    static final int STATE_CHAT_THINKING = 4;

    public PresenceHudShaderParams {
        intensity = clamp01(intensity);
        speed = Math.max(0.0F, speed);
        convergence = clamp01(convergence);
        pulse = clamp01(pulse);
        opacity = clamp01(opacity);
        morph = clamp01(morph);
        state = Math.max(STATE_IDLE, Math.min(STATE_CHAT_THINKING, state));
        task = clamp01(task);
    }

    private static float clamp01(float value) {
        return Math.max(0.0F, Math.min(1.0F, value));
    }
}
