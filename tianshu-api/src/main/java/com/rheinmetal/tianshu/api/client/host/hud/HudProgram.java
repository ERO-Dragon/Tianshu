package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The shader families a HUD program can draw with.
 *
 * <p>The host owns the programs and the assets behind them; a feature only names the family it wants, so the
 * platform's shader files and uniforms stay in the platform. Each family owns its full lifecycle, including the
 * loading entrance; there is no separate startup program.</p>
 */
public enum HudProgram {
    /** The first selectable visual family. */
    PRESET_ONE,
    /** The second selectable visual family. */
    PRESET_TWO
}
