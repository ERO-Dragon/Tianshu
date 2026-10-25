package com.rheinmetal.tianshu.api.game;

/**
 * What the platform knows about the world the player is in, in the platform's own words.
 *
 * <p>{@code kindId} is a plain string on purpose: the platform states the fact ("server_world") and a
 * module decides what that means for it. Keeping the platform free of any module's enum is what lets a
 * module leave without the kernel keeping a stub.</p>
 */
public record WorldFacts(
        String kindId,
        String stableIdentity,
        String displayName,
        String dimensionId,
        boolean writable
) {

    public static final String KIND_UNKNOWN = "unknown";
    public static final String KIND_LOCAL_WORLD = "local_world";
    public static final String KIND_SERVER_WORLD = "server_world";
    public static final String KIND_REALMS_WORLD = "realms_world";
    public static final String KIND_SHARED = "shared";

    public static final WorldFacts UNKNOWN = new WorldFacts(KIND_UNKNOWN, "", "", "", false);

    public WorldFacts {
        kindId = kindId == null || kindId.isBlank() ? KIND_UNKNOWN : kindId.trim();
        stableIdentity = stableIdentity == null ? "" : stableIdentity.trim();
        displayName = displayName == null ? "" : displayName.trim();
        dimensionId = dimensionId == null ? "" : dimensionId.trim();
        writable = writable && !KIND_UNKNOWN.equals(kindId) && !stableIdentity.isBlank();
    }

    public boolean known() {
        return !KIND_UNKNOWN.equals(kindId) && !stableIdentity.isBlank();
    }
}
