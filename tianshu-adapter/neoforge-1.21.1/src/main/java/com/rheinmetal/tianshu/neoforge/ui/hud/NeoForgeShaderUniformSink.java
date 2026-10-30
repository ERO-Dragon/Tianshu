package com.rheinmetal.tianshu.neoforge.ui.hud;

import com.mojang.blaze3d.shaders.AbstractUniform;
import com.rheinmetal.tianshu.api.client.host.hud.HudUniformSink;
import net.minecraft.client.renderer.ShaderInstance;

/**
 * The platform's only uniform writer: it resolves a feature's names against the compiled program.
 *
 * <p>The instance is reused across quads and frames. It is safe to reuse because the surface binds it to one shader
 * immediately before the feature's {@code HudProgram.bind} call and the call is synchronous on the render thread.</p>
 */
final class NeoForgeShaderUniformSink implements HudUniformSink {

    private ShaderInstance shader;
    private float frameTimeSeconds;

    void begin(ShaderInstance shader, float frameTimeSeconds) {
        this.shader = shader;
        this.frameTimeSeconds = frameTimeSeconds;
    }

    @Override
    public float frameTimeSeconds() {
        return frameTimeSeconds;
    }

    @Override
    public boolean setFloat(String name, float value) {
        AbstractUniform uniform = uniform(name);
        if (uniform == null) {
            return false;
        }
        uniform.set(value);
        return true;
    }

    @Override
    public boolean setInt(String name, int value) {
        AbstractUniform uniform = uniform(name);
        if (uniform == null) {
            return false;
        }
        uniform.set(value);
        return true;
    }

    @Override
    public boolean setVec2(String name, float x, float y) {
        AbstractUniform uniform = uniform(name);
        if (uniform == null) {
            return false;
        }
        uniform.set(x, y);
        return true;
    }

    @Override
    public boolean setVec3(String name, float x, float y, float z) {
        AbstractUniform uniform = uniform(name);
        if (uniform == null) {
            return false;
        }
        uniform.set(x, y, z);
        return true;
    }

    @Override
    public boolean setVec4(String name, float x, float y, float z, float w) {
        AbstractUniform uniform = uniform(name);
        if (uniform == null) {
            return false;
        }
        uniform.set(x, y, z, w);
        return true;
    }

    private AbstractUniform uniform(String name) {
        if (shader == null || name == null || name.isBlank()) {
            return null;
        }
        return shader.getUniform(name);
    }
}
