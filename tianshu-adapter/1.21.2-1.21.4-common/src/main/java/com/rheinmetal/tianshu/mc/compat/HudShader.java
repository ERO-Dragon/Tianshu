package com.rheinmetal.tianshu.mc.compat;

import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;

/**
 * One HUD program as this Minecraft line lets the host apply it.
 *
 * <p>The renderer always asks the same question: make this program current for one quad and give me a writer for
 * its uniforms. Which types answer that is the only part of the shader pipeline that changes between Minecraft
 * lines, so it stays behind this interface and in {@link ShaderProgramHudShader} for 1.21.3; the HUD renderer only
 * names {@code HudShader}.</p>
 */
public interface HudShader {

    /**
     * Applies the program for the next draw and binds the returned sink to it.
     *
     * <p>Called on the render thread, after the caller has taken the platform's render state and before the
     * feature's own binding runs. The sink is reused across quads and frames: a caller must not keep it, and must
     * not write through it after applying a different program.</p>
     *
     * @param frameTimeSeconds the surface's monotonic animation clock
     * @return the uniform sink, or {@code null} when the resources loaded right now provide no working program;
     *         in that case no program is current afterwards
     */
    HudUniformSink apply(float frameTimeSeconds);
}
