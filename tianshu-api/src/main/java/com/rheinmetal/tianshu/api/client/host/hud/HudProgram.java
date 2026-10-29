package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The shader families a HUD program can draw with.
 *
 * <p>The host owns the programs and the assets behind them; a feature only names the family it wants, so the
 * platform's shader files, their uniforms and their fallbacks stay in the platform.</p>
 */
public enum HudProgram {
    /** The startup visual. */
    LOADING,
    /** The first selectable visual family. */
    PRESET_ONE,
    /** The second selectable visual family. */
    PRESET_TWO
}
