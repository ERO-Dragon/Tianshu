package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * One HUD shader program owned by a feature.
 *
 * <p>The feature supplies three things that must change together: the program's namespaced id, the shader asset it
 * declares in {@link HudShaderCatalog}, and the binding from its own parameter object to the shader's uniforms. The
 * platform compiles the declared asset, owns its lifecycle and calls {@link #bind} for each quad; it keeps no list of
 * uniform names and no fixed parameter layout. A feature can therefore add a uniform, rename one or change its
 * parameters in its own jar without changing the platform.</p>
 *
 * <p>{@code P} is the feature's own parameter type. Keeping it generic is what makes the contract type-safe without
 * putting the feature's visual vocabulary into the api: the call site must pass the same type the program binds.</p>
 *
 * @param <P> the feature's parameter type
 */
public interface HudProgram<P> {

    /** The stable, namespaced identity the platform uses to find the compiled shader. */
    HudProgramId id();

    /**
     * Writes this program's uniforms for one quad.
     *
     * <p>Called by the platform on the render thread. Implementations should only call the sink and should not
     * allocate; the platform has already applied its render state and will ignore a uniform the shader does not
     * declare.</p>
     *
     * @param uniforms the host's uniform writer for the compiled shader
     * @param params   this feature's parameters for the quad
     */
    void bind(HudUniformSink uniforms, P params);
}
