package com.rheinmetal.tianshu.client.host.settings;

import java.util.List;

/**
 * The client's volume channels and how to change one.
 *
 * <p>A client preference, not a world change: it belongs to the person at the keyboard, needs no operator and reaches no
 * server. The channel ids are the game's own (master, music, weather, block, ...) so a caller can name the one it means
 * instead of guessing an index.</p>
 *
 * <p>Values are percent, 0..100, the unit the options screen shows. A port that cannot read or set a channel says so by
 * returning an empty list or {@code false} — never by throwing, because a client setting is not a game loop.</p>
 */
public interface ClientAudioSettingsPort {

    /** One channel as the options screen shows it: a stable id, its label, and where the slider sits. */
    record Channel(String id, String label, int percent) {
        public Channel {
            id = id == null ? "" : id.trim();
            label = label == null ? "" : label.trim();
            percent = Math.max(0, Math.min(100, percent));
        }
    }

    /** Every channel this client exposes, in the game's own order. */
    List<Channel> channels();

    /**
     * Moves one channel's slider.
     *
     * @return {@code false} when the id is unknown to this client, so the caller can list and try again rather than
     *         assume the change landed
     */
    boolean setVolume(String channel, int percent);
}