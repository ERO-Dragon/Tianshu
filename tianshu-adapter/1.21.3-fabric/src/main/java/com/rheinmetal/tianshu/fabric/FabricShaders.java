package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.mc.compat.ShaderProgramHudShader;
import com.rheinmetal.tianshu.mc.ui.hud.HudShaderPrograms;

import net.minecraft.resources.ResourceLocation;

import java.util.Map;

/**
 * Registers the HUD programs features declared, as this Minecraft line's shader descriptors.
 *
 * <p>NeoForge offers a shader-registration event; Fabric does not, so the same moment is taken from the resource
 * reload listener: modules declare their assets while they load, and the platform turns each declaration into a
 * program descriptor afterwards. The shader manager compiles that descriptor from the packs loaded right now and
 * owns the compiled handle; the platform only owns the descriptor's lifecycle and the failure fallback. The
 * features own the files, their uniforms, their binding and their look. Nothing here names a feature.</p>
 */
final class FabricShaders {

    /**
     * Registers every declared program, clearing the handle of any declaration that is not a usable asset id.
     *
     * <p>A visual shader is optional: a failure means that layer draws nothing, never that the client fails to
     * start.</p>
     */
    static void reload(LogSink logs) {
        for (Map.Entry<HudProgramId, String> declaration : HudShaderCatalog.declarations().entrySet()) {
            register(logs, declaration.getKey(), declaration.getValue());
        }
    }

    private static void register(LogSink logs, HudProgramId program, String shaderId) {
        ResourceLocation location = ResourceLocation.tryParse(shaderId);
        if (location == null) {
            HudShaderPrograms.register(program, null);
            logs.warn("fabric.hud.shader_declaration_invalid id=" + shaderId);
            return;
        }
        HudShaderPrograms.register(program, new ShaderProgramHudShader(location));
    }

    private FabricShaders() {
    }
}
