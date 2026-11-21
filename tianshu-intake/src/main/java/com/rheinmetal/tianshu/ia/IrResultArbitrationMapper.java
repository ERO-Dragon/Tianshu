package com.rheinmetal.tianshu.ia;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.ia.contract.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.ia.contract.IrResultPayload;

final class IrResultArbitrationMapper {
    private static final long REQUEST_TTL_MILLIS = 10_000L;

    DialogueArbitrationRequestPayload map(TianshuEnvelope envelope, IrResultPayload payload, String playerId) {
        long timestampMillis = payload.timestampMillis();
        return new DialogueArbitrationRequestPayload(
                envelope.envelopeId(),
                envelope.header().sourceId(),
                playerId,
                Integer.toString(payload.turnId()),
                payload.sessionId(),
                payload.repairedText(),
                payload.normalizedText(),
                payload.objectMatches(),
                payload.moduleMatches(),
                timestampMillis,
                timestampMillis + REQUEST_TTL_MILLIS
        );
    }
}
