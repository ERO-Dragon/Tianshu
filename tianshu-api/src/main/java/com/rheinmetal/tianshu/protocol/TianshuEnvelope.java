package com.rheinmetal.tianshu.protocol;

import java.util.Objects;

public final class TianshuEnvelope {
    private final EnvelopeHeader header;
    private final ITianshuPayload payload;

    public TianshuEnvelope(EnvelopeHeader header, ITianshuPayload payload) {
        this.header = Objects.requireNonNull(header, "header");
        this.payload = payload;
    }

    public EnvelopeHeader header() {
        return header;
    }

    public ITianshuPayload payload() {
        return payload;
    }

    public String envelopeId() {
        return header.envelopeId();
    }

    public String traceId() {
        return header.traceId();
    }

    public String parentId() {
        return header.parentId();
    }
}
