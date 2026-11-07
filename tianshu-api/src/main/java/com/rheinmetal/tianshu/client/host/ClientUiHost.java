package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

public interface ClientUiHost {
    void openSettings();

    void requestSettingsRefresh();

    void showStatus(UiText text, long durationMillis);

    /**
     * Sets the typed-dialogue bar's labels.
     *
     * <p>The module that owns the assistant's name calls this while it assembles; the host keeps a neutral
     * fallback so the bar still works when no such module is installed. Both values are language keys, so a
     * language switch re-resolves them instead of freezing one wording at assembly time.</p>
     */
    default void setTextInputLabels(UiText title, UiText hint) {
    }

    /**
     * Whether this host can open the game's key-binding screen.
     *
     * <p>A feature page shows a "set key" action only where the host can actually carry it out; a platform
     * that has no such screen must not render a button that does nothing.</p>
     */
    default boolean supportsKeyBindings() {
        return false;
    }

    /** Opens the game's key-binding screen, returning to the current screen when it closes. */
    default void openKeyBindings() {
    }
}
