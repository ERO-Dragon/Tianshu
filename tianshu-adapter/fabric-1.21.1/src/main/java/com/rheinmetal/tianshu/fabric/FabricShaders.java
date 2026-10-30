package com.rheinmetal.tianshu.fabric;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.api.client.host.hud.HudProgramId;
import com.rheinmetal.tianshu.api.client.host.hud.HudShaderCatalog;
import com.rheinmetal.tianshu.mc.ui.hud.HudShaderPrograms;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceProvider;

import java.io.IOException;
import java.util.Map;

/**
 * Creates the HUD programs features declared, from the resource pack that is now loaded.
 *
 * <p>NeoForge offers a shader-registration event; Fabric does not, so the same moment is taken from the resource
 * reload listener: by then the shader assets exist and the client can compile them. The platform owns the
 * {@code ShaderInstance} lifecycle and the failure fallback; the features own the files, their uniforms, their
 * binding and their look. Nothing here names a feature.</p>
 */
final class FabricShaders {

    /**
     * Compiles every declared program, clearing the handle of any that cannot be built.
     *
     * <p>A visual shader is optional: a failure means that layer draws nothing, never that the client fails to
     * start.</p>
     */
    static void reload(LogSink logs) {
        Minecraft minecraft = Minecraft.getInstance();
        ResourceProvider resources = minecraft == null ? null : minecraft.getResourceManager();
        if (resources == null) {
            return;
        }
        for (Map.Entry<HudProgramId, String> declaration : HudShaderCatalog.declarations().entrySet()) {
            register(logs, resources, declaration.getKey(), declaration.getValue());
        }
    }

    private static void register(LogSink logs, ResourceProvider resources, HudProgramId program, String shaderId) {
        ResourceLocation location = ResourceLocation.tryParse(shaderId);
        if (location == null) {
            HudShaderPrograms.register(program, null);
            logs.warn("fabric.hud.shader_declaration_invalid id=" + shaderId);
            return;
        }
        try {
            HudShaderPrograms.register(program,
                    new ShaderInstance(resources, location.toString(), DefaultVertexFormat.POSITION_TEX));
        } catch (IOException | RuntimeException failure) {
            HudShaderPrograms.register(program, null);
            logs.error("fabric.hud.shader_registration_failed name=" + location, failure);
        }
    }

    private FabricShaders() {
    }
}
