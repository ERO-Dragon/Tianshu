package com.rheinmetal.tianshu.api.game;

/**
 * Which entity to read, by id.
 *
 * <p>The id is the one {@code body.scan} reported: that is how a caller learns an entity exists, so it is also how it asks
 * what that entity is.</p>
 */
public record EntityInspectRequest(String entityId) {

    public EntityInspectRequest {
        entityId = entityId == null ? "" : entityId.trim();
    }
}