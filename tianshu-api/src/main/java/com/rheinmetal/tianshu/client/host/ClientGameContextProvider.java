package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;

import java.util.Set;

/**
 * The platform side of the on-demand capture: fill the groups of one presence query.
 *
 * <p>Called on the client thread for a request that is about to be answered, never on a timer. The
 * implementation reads live Minecraft objects and returns values; it does not cache, and it does not decide
 * which groups a consumer is allowed to ask for.</p>
 */
@FunctionalInterface
public interface ClientGameContextProvider {
    PresenceContextSnapshot captureContext(Set<PresenceContextGroup> groups);
}
