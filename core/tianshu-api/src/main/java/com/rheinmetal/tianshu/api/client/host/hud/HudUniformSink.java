package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The typed values a feature may write into its own HUD shader.
 *
 * <p>The host resolves each name against the compiled program, so a feature can add or rename a uniform in its shader
 * and binder without the platform knowing the name. A name the compiled program does not declare, including a uniform
 * removed by the shader compiler, is ignored and reported as {@code false}; that is a normal outcome, not a frame
 * failure.</p>
 *
 * <p>The host does not inject any named uniform of its own. A shader that wants time reads it once and writes it under
 * its own uniform name; the frame's matrices are supplied by the platform's shader pipeline, not by this sink.</p>
 */
public interface HudUniformSink {

    /** The current frame's elapsed time in seconds, for a program that exposes a time uniform. */
    float frameTimeSeconds();

    /** Writes one float uniform; returns false when the compiled program has no such uniform. */
    boolean setFloat(String name, float value);

    /** Writes one int uniform; returns false when the compiled program has no such uniform. */
    boolean setInt(String name, int value);

    /** Writes one vec2 uniform; returns false when the compiled program has no such uniform. */
    boolean setVec2(String name, float x, float y);

    /** Writes one vec3 uniform; returns false when the compiled program has no such uniform. */
    boolean setVec3(String name, float x, float y, float z);

    /** Writes one vec4 uniform; returns false when the compiled program has no such uniform. */
    boolean setVec4(String name, float x, float y, float z, float w);
}
