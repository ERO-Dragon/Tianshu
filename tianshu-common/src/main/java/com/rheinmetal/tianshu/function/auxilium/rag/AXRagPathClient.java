package com.rheinmetal.tianshu.function.auxilium.rag;

import com.rheinmetal.tianshu.function.auxilium.AXModule;
import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathRequestPayload;
import com.rheinmetal.tianshu.protocol.payload.LlmRagPathResultPayload;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

public final class AXRagPathClient {
    private static final String DEFAULT_AGENT_ID = "default_agent";
    private final AXProtocolAdapter adapter;
    private final Map<String, String> requestIdByEnvelopeId = new ConcurrentHashMap<>();
    private final Map<String, AXRagPathResolution> resolutionByRequestId = new ConcurrentHashMap<>();

    public AXRagPathClient(AXProtocolAdapter adapter) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
    }

    public String requestCurrent() {
        String requestId = "AX.rag.path." + Long.toUnsignedString(System.currentTimeMillis(), 36);
        TianshuEnvelope envelope = adapter.requestRagPath(new LlmRagPathRequestPayload(requestId, AXModule.MODULE_ID, DEFAULT_AGENT_ID));
        requestIdByEnvelopeId.put(envelope.envelopeId(), requestId);
        return requestId;
    }

    public Optional<AXRagPathResolution> latest() {
        return resolutionByRequestId.values().stream()
                .filter(AXRagPathResolution::valid)
                .findFirst();
    }

    public boolean handleResult(String envelopeId, LlmRagPathResultPayload payload) {
        if (payload == null) {
            return false;
        }
        String requestId = requestIdByEnvelopeId.remove(envelopeId);
        if (requestId == null || !requestId.equals(payload.requestId())) {
            return false;
        }
        AXRagPathResolution resolution = AXRagPathResolution.fromPayload(payload);
        if (resolution.valid()) {
            resolutionByRequestId.clear();
            resolutionByRequestId.put(payload.requestId(), resolution);
            return true;
        }
        return false;
    }

    public void clear() {
        requestIdByEnvelopeId.clear();
        resolutionByRequestId.clear();
    }
}
