package com.rheinmetal.tianshu.function.presence;

import com.rheinmetal.tianshu.client.api.text.UiText;
import com.rheinmetal.tianshu.client.host.ClientTextProvider;

import java.util.Arrays;
import java.util.stream.Collectors;

public interface PresenceTextProvider {
    PresenceTextProvider NOOP = new PresenceTextProvider() {
        @Override
        public boolean exists(String key) {
            return false;
        }

        @Override
        public String text(String key, Object... args) {
            if (key == null || key.isBlank()) {
                return "";
            }
            if (args == null || args.length == 0) {
                return key;
            }
            return key + " " + Arrays.stream(args)
                    .map(String::valueOf)
                    .collect(Collectors.joining(" "));
        }
    };

    /**
     * Presence's own words, resolved through the platform's text port.
     *
     * <p>A module that ships its own translations still must not own the translation machinery: the platform knows the
     * language, the resource pack and the fallback behaviour, so presence asks it and keeps only the keys.</p>
     */
    static PresenceTextProvider of(ClientTextProvider texts) {
        ClientTextProvider effective = texts == null ? ClientTextProvider.NOOP : texts;
        return new PresenceTextProvider() {
            @Override
            public boolean exists(String key) {
                return effective.hasKey(key);
            }

            @Override
            public String text(String key, Object... args) {
                if (key == null || key.isBlank()) {
                    return "";
                }
                return effective.text(UiText.key(key, args));
            }
        };
    }

    boolean exists(String key);

    String text(String key, Object... args);
}
