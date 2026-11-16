package com.rheinmetal.tianshu.api.entity;

import java.util.Optional;

/**
 * The adapter's ability to bring a body into the world.
 *
 * <p>One port, two bases: the implementation decides from {@link EntityBodySpec#playerLike()} whether the body is
 * created on a player-capable base (fakeplayer) or on the ordinary entity base. The platform never branches on the
 * loader, and a caller never learns which base it got — only that the returned actor is commandable.</p>
 *
 * <p>{@link Optional#empty()} means "this side cannot create a body right now" (no level loaded, a dedicated server
 * without the client half). It is not an error: a tool turns it into {@code UNAVAILABLE}.</p>
 */
public interface EntityCreationPort {

    Optional<EntityActor> create(EntityBodySpec spec);
}