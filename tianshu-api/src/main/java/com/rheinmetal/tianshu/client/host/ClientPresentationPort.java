package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

/**
 * The client's local presentation primitives.
 *
 * <p>These are effects on <em>this</em> player's screen or speakers and nothing else: no packet is sent, no server
 * decides, and no other player sees or hears them. A module that wants a message to reach everyone else asks the
 * server-side broadcast capability instead — the two are deliberately different ports.</p>
 *
 * <p>All methods run on the client thread and are cheap (they hand a component to an existing vanilla overlay).
 * They are for event-time feedback, not for per-frame drawing: a caller that wants to update every tick should use
 * a HUD layer instead, so the text is not rebuilt and re-queued sixty times a second.</p>
 */
public interface ClientPresentationPort {

    /** One line in the local chat bar. */
    void chat(UiText text);

    /** One line above the hotbar; the vanilla overlay fades it out on its own. */
    void actionBar(UiText text);

    /** A toast in the top-right corner: title plus a short description. */
    void toast(UiText title, UiText description);

    /** The centre-screen title and subtitle, with the vanilla fade timings. */
    void title(UiText title, UiText subtitle, int fadeInTicks, int stayTicks, int fadeOutTicks);

    /**
     * Plays a registered sound locally.
     *
     * @param soundId a registry id such as {@code minecraft:entity.experience_orb.pickup}
     * @param volume  linear volume, {@code 1.0} is normal
     * @param pitch   playback pitch, {@code 1.0} is normal
     */
    void playSound(String soundId, float volume, float pitch);
}
