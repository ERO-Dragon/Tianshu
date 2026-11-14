package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.function.presence.hud.PresenceHudProgram;

/**
 * The shader assets presence ships.
 *
 * <p>They live in presence's own namespace and jar: the look of the HUD is a product decision, so the platform only
 * creates programs for assets a feature declared. The program ids and uniform bindings live beside the assets in
 * {@link PresenceHudProgram}; when presence is absent nothing is declared, and its icon is simply not drawn.</p>
 */
public final class PresenceHudShaders {

    static final String SHADER_PRESET_ONE = "tianshu_presence:presence_preset_one";
    static final String SHADER_PRESET_TWO = "tianshu_presence:presence_preset_two";

    private PresenceHudShaders() {
    }

    /** Declares the two program assets; each owns its loading entrance. */
    public static void declare() {
        HudShaderCatalog.declare(PresenceHudProgram.PRESET_ONE.id(), SHADER_PRESET_ONE);
        HudShaderCatalog.declare(PresenceHudProgram.PRESET_TWO.id(), SHADER_PRESET_TWO);
    }
}
