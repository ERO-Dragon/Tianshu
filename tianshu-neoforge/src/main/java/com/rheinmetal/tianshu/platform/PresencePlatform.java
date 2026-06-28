package com.rheinmetal.tianshu.platform;

import com.rheinmetal.tianshu.client.presence.context.PresenceContextGroup;
import com.rheinmetal.tianshu.client.presence.model.PresenceContextSnapshot;
import com.rheinmetal.tianshu.client.presence.model.PresenceInputKind;
import com.rheinmetal.tianshu.protocol.payload.PresenceWorldEventPayload;

import java.util.List;
import java.util.Set;

public interface PresencePlatform {
    PresenceContextSnapshot captureContext(Set<PresenceContextGroup> groups, PresenceInputKind inputKind);

    List<PresenceWorldEventPayload> collectAdvancementUpdate(Object nativePacket);
}
