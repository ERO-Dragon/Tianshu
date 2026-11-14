package com.rheinmetal.tianshu.api.client.host.hud;

/**
 * The drawing operations a HUD layer may use, implemented by the host.
 *
 * <p>A feature decides <em>what</em> the player sees; the platform decides how pixels are produced. This interface is
 * the whole of that contract: it carries no game types (no graphics object, font, pose stack or resource location), so
 * a feature that ships for every loader and every version can compose a HUD, and the platform can change its renderer
 * without touching the feature.</p>
 *
 * <p>Coordinates are GUI-scaled pixels. Every method may be called many times per frame; implementations must not
 * allocate per call beyond what the shape itself requires. The platform owns every piece of render state (blend,
 * depth, pose, shader, tint) around each call, so a layer cannot leave the frame in a broken state.</p>
 */
public interface HudSurface {

    /** The GUI-scaled width of the frame being drawn. */
    int width();

    /** The GUI-scaled height of the frame being drawn. */
    int height();

    /** The width {@code text} would occupy with the current font, for centring and panel sizing. */
    int textWidth(String text);

    /** The current font's line height, for stacking text and sizing panels. */
    int lineHeight();

    /** A solid rectangle; the colour is ARGB and the top-left corner is inclusive. */
    void fill(float left, float top, float right, float bottom, int argb);

    /** One line of text, anchored at its top-left corner; a blank string draws nothing. */
    void text(String text, float x, float y, int argb, boolean shadow);

    /**
     * Draws the whole of {@code textureId} as a square centred on {@code centreX}/{@code centreY}.
     *
     * <p>The platform applies the rotation, the scale and the alpha around the call, so the caller never touches a
     * pose stack. A missing texture draws nothing — a resource pack without the icon must degrade the marker, never
     * the frame.</p>
     */
    void blit(
            String textureId,
            float centreX,
            float centreY,
            float width,
            float height,
            float rotationRadians,
            float scale,
            float alpha
    );

    /**
     * Draws one quad through a feature-declared shader program.
     *
     * <p>The feature owns the program, its shader assets and its uniforms: it supplies a {@link HudProgram} whose
     * binder writes the feature's own parameters through {@link HudUniformSink}. The host owns the compiled program's
     * lifecycle, the frame clock and all render state around the call. An unavailable or failing program draws
     * nothing, and the caller treats that as "this element is absent" rather than drawing a second version underneath;
     * a feature that wants a fallback draws it itself.</p>
     *
     * @param program the feature's program and uniform binder
     * @param params  the matching feature parameter object, never interpreted by the host
     */
    <P> void shaderRect(HudProgram<P> program, float left, float top, float right, float bottom, P params);
}
