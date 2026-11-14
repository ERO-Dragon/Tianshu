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
}
