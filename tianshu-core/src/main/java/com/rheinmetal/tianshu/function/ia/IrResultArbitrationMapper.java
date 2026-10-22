package com.rheinmetal.tianshu.function.ia;

import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.dialogue.payload.DialogueArbitrationRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.IrResultPayload;

final class IrResultArbitrationMapper {
    private static final String LOCAL_DIALOGUE_SCOPE = "local";
    private static final long REQUEST_TTL_MILLIS = 10_000L;

    DialogueArbitrationRequestPayload map(TianshuEnvelope envelope, IrResultPayload payload) {
        long timestampMillis = payload.timestampMillis();
        return new DialogueArbitrationRequestPayload(
                envelope.envelopeId(),
                envelope.header().sourceId(),
                LOCAL_DIALOGUE_SCOPE,
                Integer.toString(payload.turnId()),
                payload.sessionId(),
                payload.repairedText(),
                payload.normalizedText(),
                payload.voiceMatches(),
                payload.matchedItemIds(),
                payload.matchedEntityTypeIds(),
                timestampMillis,
                timestampMillis + REQUEST_TTL_MILLIS
        );
    }
}
