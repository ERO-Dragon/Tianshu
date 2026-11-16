package com.rheinmetal.tianshu.api.entity;

import java.util.Objects;

/**
 * A request to bring one entity into the world as a commandable body.
 *
 * <p>{@code playerLike} is the identity that decides the base: a player-like body is created on a player-capable base,
 * so the server's own player rules and permissions bound it; an ordinary body is created on the plain entity base and
 * its reach comes from the module that asked for it plus the platform's switches.</p>
 *
 * @param bodyId       the body identity callers use in tool calls
 * @param entityTypeId the declared {@link EntityTypeSpec#id()} to instantiate; unused for a player-like body
 * @param playerLike   whether the body takes part in player rules
 */
public record EntityBodySpec(String bodyId, String entityTypeId, boolean playerLike) {

    public EntityBodySpec {
        bodyId = requireText(bodyId, "bodyId");
        if (!playerLike) {
            entityTypeId = requireText(entityTypeId, "entityTypeId");
        }
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        if (trimmed.indexOf(':') < 0) {
            throw new IllegalArgumentException(field + " must be namespaced (modId:name): " + value);
        }
        return trimmed;
    }
}