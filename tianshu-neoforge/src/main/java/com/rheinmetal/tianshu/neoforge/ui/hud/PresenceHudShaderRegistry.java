package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualPreset;
import com.rheinmetal.tianshu.client.presence.hud.PresenceHudVisualState;
import net.minecraft.client.renderer.ShaderInstance;

/** Holds the client-thread shader handles supplied by NeoForge's shader lifecycle. */
public final class PresenceHudShaderRegistry {
    private static volatile ShaderInstance loading;
    private static volatile ShaderInstance presetOne;
    private static volatile ShaderInstance presetTwo;

    private PresenceHudShaderRegistry() {
    }

    public static void setLoading(ShaderInstance shader) {
        loading = shader;
    }

    public static void setPresetOne(ShaderInstance shader) {
        presetOne = shader;
    }

    public static void setPresetTwo(ShaderInstance shader) {
        presetTwo = shader;
    }

    public static ShaderInstance shaderFor(PresenceHudVisualState state, PresenceHudVisualPreset preset) {
        if (state == PresenceHudVisualState.LOADING) {
            return loading;
        }
        return (preset == PresenceHudVisualPreset.PRESET_TWO ? presetTwo : presetOne);
    }

    public static void clear() {
        loading = null;
        presetOne = null;
        presetTwo = null;
    }
}
