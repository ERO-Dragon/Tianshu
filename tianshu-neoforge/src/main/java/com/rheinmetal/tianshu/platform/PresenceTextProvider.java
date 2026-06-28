package com.rheinmetal.tianshu.platform;

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

    boolean exists(String key);

    String text(String key, Object... args);
}
