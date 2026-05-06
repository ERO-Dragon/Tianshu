package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.protocol.PacketType;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.StreamTextPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolContext;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolRuntime;
import com.rheinmetal.tianshu.function.tts.TtsWorker;

public final class TtsModule {
    private final TtsWorker ttsWorker;
    private final TtsProtocolAdapter adapter;

    public TtsModule(TtsWorker ttsWorker, ProtocolRuntime runtime) {
        this.ttsWorker = ttsWorker;
        this.adapter = new TtsProtocolAdapter(runtime);
    }

    public void register() {
        adapter.registerSpeakCapability(this::handleSpeak);
        adapter.subscribeLlmStream(this::handleLlmStream);
    }

    private void handleSpeak(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof TtsSpeakPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "TTS payload is invalid", null);
            return;
        }
        ttsWorker.speakProtocolText(payload.text(), payload.interruptCurrent());
        context.complete(envelope.envelopeId());
    }

    private void handleLlmStream(TianshuEnvelope envelope, ProtocolContext context) {
        if (!(envelope.payload() instanceof StreamTextPayload payload)) {
            context.fail(envelope.envelopeId(), "INVALID_PAYLOAD", "LLM stream payload is invalid", null);
            return;
        }
        if (envelope.header().packetType() == PacketType.STREAM_END || payload.last()) {
            ttsWorker.finishProtocolPlayback();
            return;
        }
        ttsWorker.handleProtocolChunk(payload.text());
    }
}
