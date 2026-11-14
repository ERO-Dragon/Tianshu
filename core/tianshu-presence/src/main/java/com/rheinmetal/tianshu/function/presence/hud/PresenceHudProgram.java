package com.rheinmetal.tianshu.function.presence.hud;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;

/**
 * Presence's two HUD programs, including the contract between its parameters and its shader uniforms.
 *
 * <p>The whole visual contract lives on this side of the api boundary: the program ids, the parameter record and the
 * uniform names. A released platform only compiles the declared assets and calls {@link #bind}; it never learns these
 * names, so presence can add or rename a uniform, expose another control or change the shader's parameter layout in a
 * presence-only update.</p>
 */
public enum PresenceHudProgram implements HudProgram<PresenceHudShaderParams> {
    PRESET_ONE("tianshu_presence:preset_one"),
    PRESET_TWO("tianshu_presence:preset_two");

    private final HudProgramId id;

    PresenceHudProgram(String id) {
        this.id = new HudProgramId(id);
    }

    @Override
    public HudProgramId id() {
        return id;
    }

    @Override
    public void bind(HudUniformSink uniforms, PresenceHudShaderParams params) {
        uniforms.setFloat("Time", uniforms.frameTimeSeconds());
        uniforms.setFloat("Intensity", params.intensity());
        uniforms.setFloat("Speed", params.speed());
        uniforms.setFloat("Convergence", params.convergence());
        uniforms.setFloat("Pulse", params.pulse());
        uniforms.setFloat("Opacity", params.opacity());
        uniforms.setFloat("Morph", params.morph());
        uniforms.setFloat("State", params.state());
        uniforms.setFloat("Task", params.task());
    }
}
