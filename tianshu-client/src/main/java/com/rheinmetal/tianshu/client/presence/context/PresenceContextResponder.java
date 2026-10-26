package com.rheinmetal.tianshu.client.presence.context;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.PresenceContextSnapshotPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;

import java.util.List;

/**
 * Sends the facts of one answered query and closes the request.
 *
 * <p>The coordinator decides <em>what</em> the answer is; the protocol adapter decides how it travels. Keeping
 * the two apart is what lets the coordinator be exercised without a protocol runtime, and it stops the query
 * path from depending on the protocol adapter type.</p>
 */
@FunctionalInterface
public interface PresenceContextResponder {
    void respond(
            TianshuEnvelope request,
            String requestId,
            List<PresenceContextSnapshotPayload.FactPayload> facts,
            ProtocolContext context
    );
}
