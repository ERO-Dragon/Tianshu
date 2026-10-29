package com.rheinmetal.tianshu.function.ia.settings;

import com.rheinmetal.tianshu.api.config.ModuleConfigStore;

import java.util.Objects;

/**
 * The persisted default-owner choice.
 *
 * <p>Kept as two keys — "off" and "which participant" — so that turning the default owner off does not have to
 * erase the participant the player picked, and so that "never configured" stays distinguishable from "off". A
 * picked module that is currently not installed keeps its key: the choice comes back with the module, it just has
 * no effect while the module is away.</p>
 */
public final class IaDefaultOwnerConfig implements IaDefaultOwnerSettings {
    private static final String KEY_DISABLED = "defaultOwnerDisabled";
    private static final String KEY_PARTICIPANT = "defaultOwnerParticipantId";

    private final ModuleConfigStore store;

    public IaDefaultOwnerConfig(ModuleConfigStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public boolean isDisabled() {
        return store.getBoolean(KEY_DISABLED, false);
    }

    @Override
    public String participantId() {
        return store.getString(KEY_PARTICIPANT, "");
    }

    /** Points the default owner at {@code participantId}, or at nobody when it is blank. */
    public void select(String participantId) {
        String chosen = participantId == null ? "" : participantId.trim();
        store.setBoolean(KEY_DISABLED, chosen.isEmpty());
        store.setString(KEY_PARTICIPANT, chosen);
        store.save();
    }
}