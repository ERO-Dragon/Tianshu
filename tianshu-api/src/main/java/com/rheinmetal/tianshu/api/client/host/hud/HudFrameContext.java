package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * One frame's clocks and resolution, handed to every layer.
 *
 * <p>Two clocks on purpose: animation phases use {@code renderMillis} (monotonic, advanced per rendered frame), while
 * anything compared against a deadline — a lease, a cache entry — uses {@code wallMillis}. Mixing them is how a ring
 * silently drifts by however long the process has been up.</p>
 *
 * @param renderMillis elapsed monotonic render time
 * @param wallMillis   the wall clock at this frame
 * @param width        GUI-scaled width
 * @param height       GUI-scaled height
 */
public record HudFrameContext(long renderMillis, long wallMillis, int width, int height) {
    public HudFrameContext {
        renderMillis = Math.max(0L, renderMillis);
        width = Math.max(1, width);
        height = Math.max(1, height);
    }
}
