package com.rheinmetal.tianshu.client.host;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;

import java.util.Set;

@FunctionalInterface
public interface ClientGameContextProvider {
    PresenceContextSnapshot captureContext(Set<PresenceContextGroup> groups, PresenceInputKind inputKind);
}
