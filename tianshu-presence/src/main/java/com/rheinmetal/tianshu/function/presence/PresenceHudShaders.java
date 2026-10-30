package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.api.client.host.hud.HudProgram;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;

/**
 * The shader assets presence ships.
 *
 * <p>They live in presence's own namespace and jar: the look of the HUD is a product decision, so the platform only
 * creates programs for families a feature declared. When presence is absent nothing is declared, and its icon is simply
 * not drawn.</p>
 */
public final class PresenceHudShaders {

    public static final String PRESET_ONE = "tianshu_presence:presence_preset_one";
    public static final String PRESET_TWO = "tianshu_presence:presence_preset_two";

    private PresenceHudShaders() {
    }

    /** Declares the two families; each owns its loading entrance. */
    public static void declare() {
        HudShaderCatalog.declare(HudProgram.PRESET_ONE, PRESET_ONE);
        HudShaderCatalog.declare(HudProgram.PRESET_TWO, PRESET_TWO);
    }
}
