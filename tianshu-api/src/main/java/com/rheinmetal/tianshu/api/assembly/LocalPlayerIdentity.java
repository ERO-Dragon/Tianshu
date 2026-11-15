package com.rheinmetal.tianshu.api.assembly;

import java.util.Optional;

/**
 * The local player's identity, as the client knows it.
 *
 * <p>The conversation runtime runs on the client and needs to name the player a turn belongs to; the scope model
 * still carries a shared placeholder, so this port is the identity source until that changes. Empty when no local
 * player exists (menu, dedicated server).</p>
 */
public interface LocalPlayerIdentity {

    /** The local player's UUID as a string, when one is present. */
    Optional<String> playerId();

    /** What the client knows about the local player's operator permission. */
    boolean operator();
}