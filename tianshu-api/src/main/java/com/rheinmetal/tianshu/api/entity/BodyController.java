package com.rheinmetal.tianshu.api.entity;

import com.rheinmetal.tianshu.api.tool.ToolCaller;
import com.rheinmetal.tianshu.api.tool.ToolException;

import java.util.Optional;
import java.util.UUID;

/** The requesting server module and, for a client request, the authenticated player on its connection. */
public record BodyController(String moduleId, UUID playerId) {
    public BodyController {
        if (moduleId == null || moduleId.isBlank()) {
            throw BodyFailure.CONTROLLER_REQUIRED.exception();
        }
    }

    /** Use at a module's server-side entry point. Player identity comes from the caller supplied by the host. */
    public static BodyController forRequest(String moduleId, ToolCaller caller) {
        String player;
        switch (caller.kind()) {
            case MOD, AGENT -> {
                if (!moduleId.equals(caller.id())) {
                    throw BodyFailure.CONTROLLER_MISMATCH.exception();
                }
                player = caller.actingFor();
            }
            case PLAYER -> player = caller.id();
            default -> throw BodyFailure.CONTROLLER_REQUIRED.exception();
        }
        try {
            return new BodyController(moduleId, player == null ? null : UUID.fromString(player));
        } catch (IllegalArgumentException invalidPlayer) {
            throw BodyFailure.PLAYER_DENIED.exception();
        }
    }

    /** A local module controls its own bodies. A client request must also match the player recorded at creation. */
    public Optional<ToolException> refusal(String ownerModule, UUID ownerPlayer) {
        if (!moduleId.equals(ownerModule)) {
            return Optional.of(BodyFailure.CONTROLLER_MISMATCH.exception());
        }
        if (playerId != null && !playerId.equals(ownerPlayer)) {
            return Optional.of(BodyFailure.PLAYER_DENIED.exception());
        }
        return Optional.empty();
    }
}
