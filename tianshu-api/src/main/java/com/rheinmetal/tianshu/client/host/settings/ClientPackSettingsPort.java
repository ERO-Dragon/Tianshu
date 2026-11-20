package com.rheinmetal.tianshu.client.host.settings;

import java.util.List;

/**
 * The resource packs this client has and which of them are switched on.
 *
 * <p>Switching a pack changes what the player sees, not what the world is: it stays client-local, needs no operator and
 * touches no server. A change is applied by reloading the client's resources, which the adapter does as part of
 * {@link #setEnabled} so a caller cannot leave the list and the screen disagreeing.</p>
 */
public interface ClientPackSettingsPort {

    /** One installed pack; {@code required} means the game will not let it be switched off. */
    record Pack(String id, String label, boolean enabled, boolean required) {
        public Pack {
            id = id == null ? "" : id.trim();
            label = label == null ? "" : label.trim();
        }
    }

    /** Every installed pack, in the order the game lists them. */
    List<Pack> packs();

    /**
     * Switches one pack on or off and applies the result.
     *
     * @return {@code false} when the id is unknown or the pack is required, so "it did not change" is an answer the
     *         caller can act on
     */
    boolean setEnabled(String id, boolean enabled);
}