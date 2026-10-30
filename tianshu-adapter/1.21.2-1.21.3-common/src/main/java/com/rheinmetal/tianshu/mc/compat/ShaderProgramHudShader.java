package com.rheinmetal.tianshu.mc.compat;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import net.minecraft.client.renderer.CompiledShaderProgram;
import net.minecraft.client.renderer.ShaderDefines;
import net.minecraft.client.renderer.ShaderProgram;
import net.minecraft.resources.ResourceLocation;

import java.util.Objects;

/**
 * The 1.21.3 shader pipeline: a program is a {@link ShaderProgram} descriptor — an asset id, a vertex format and
 * its defines — that the shader manager compiles from the loaded packs, and applying it yields the
 * {@link CompiledShaderProgram} whose uniforms are written per quad.
 *
 * <p>The compiled handle belongs to the current resource-load generation. It is resolved on every use instead of
 * being stored beside the descriptor, so a reload that closes the previous generation's programs cannot leave the
 * HUD drawing through a handle that no longer exists behind it.</p>
 */
public final class ShaderProgramHudShader implements HudShader {

    /**
     * Program configs are identified by their path below {@code shaders/}. A feature declares its program by the
     * name its files have inside the {@code shaders/core} folder — the layout the shader asset contract has always
     * used — so that folder is part of the identity here, not part of the declared name.
     */
    private static final String PROGRAM_FOLDER = "core";

    private final ShaderProgram descriptor;
    private final ShaderUniformSink uniforms = new ShaderUniformSink();

    public ShaderProgramHudShader(ResourceLocation declaredAsset) {
        Objects.requireNonNull(declaredAsset, "declaredAsset");
        this.descriptor = new ShaderProgram(
                ResourceLocation.fromNamespaceAndPath(
                        declaredAsset.getNamespace(),
                        PROGRAM_FOLDER + "/" + declaredAsset.getPath()
                ),
                DefaultVertexFormat.POSITION_TEX,
                ShaderDefines.EMPTY
        );
    }

    @Override
    public HudUniformSink apply(float frameTimeSeconds) {
        CompiledShaderProgram program = RenderSystem.setShader(descriptor);
        if (program == null) {
            return null;
        }
        uniforms.begin(program, frameTimeSeconds);
        return uniforms;
    }
}
