package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.api.text.UiText;

public interface ClientTextProvider {

    /** Stand-in for hosts that render no text at all (headless assembly, tests). */
    ClientTextProvider NOOP = new ClientTextProvider() {
        @Override
        public String text(UiText text) {
            return "";
        }

        @Override
        public String currentLanguage() {
            return "en_us";
        }

        @Override
        public boolean hasKey(String key) {
            return false;
        }
    };

    String text(UiText text);

    String currentLanguage();

    /**
     * Whether a translation key resolves in the current language.
     *
     * <p>Settings pages use this to decide between showing a translation and showing the raw value they
     * were given, so a missing key must not fall back to echoing the key itself.</p>
     */
    boolean hasKey(String key);
}
