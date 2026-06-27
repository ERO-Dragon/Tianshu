package com.rheinmetal.tianshu.client.presence;

public record PresenceWorldEnvironment(
        boolean raining,
        boolean thundering,
        long dayTimeTicks,
        String biomeId,
        String biomeDisplayName
) {
    public PresenceWorldEnvironment {
        dayTimeTicks = Math.max(0L, dayTimeTicks);
        biomeId = clean(biomeId);
        biomeDisplayName = clean(biomeDisplayName);
    }

    public static PresenceWorldEnvironment empty() {
        return new PresenceWorldEnvironment(false, false, 0L, "", "");
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
