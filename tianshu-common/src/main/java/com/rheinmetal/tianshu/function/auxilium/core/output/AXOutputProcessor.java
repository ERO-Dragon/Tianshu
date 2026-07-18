package com.rheinmetal.tianshu.function.auxilium.core.output;

import com.rheinmetal.tianshu.function.auxilium.AXProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.protocol.payload.TtsPlaybackPlacement;
import com.rheinmetal.tianshu.protocol.payload.TtsSpeakPayload;
import com.rheinmetal.tianshu.protocol.payload.TtsTextInputMode;
import com.rheinmetal.tianshu.protocol.payload.TtsVoiceOptions;

import java.util.Objects;

public final class AXOutputProcessor {
    private final AXProtocolAdapter adapter;
    private final AXOutputSettings settings;
    private final AXChatOutputSink chatSink;

    public AXOutputProcessor(AXProtocolAdapter adapter, AXOutputSettings settings, AXChatOutputSink chatSink) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.settings = settings == null ? AXOutputSettings.DEFAULT : settings;
        this.chatSink = chatSink == null ? AXChatOutputSink.NOOP : chatSink;
    }

    public AXOutputTurn startTurn(TianshuEnvelope parent, AXOutputContext context, boolean chatLane) {
        return new AXOutputTurn(parent, context, chatLane);
    }

    public void interrupt(String reasonCode) {
        if (settings.ttsEnabled()) {
            adapter.stopOwnTtsOutput(reasonCode);
        }
    }

    public final class AXOutputTurn {
        private final TianshuEnvelope parent;
        private final AXOutputContext context;
        private final boolean chatLane;
        private final AXSentenceBuffer ttsBuffer = new AXSentenceBuffer();
        private boolean streamSeen;
        private boolean closed;

        private AXOutputTurn(TianshuEnvelope parent, AXOutputContext context, boolean chatLane) {
            this.parent = parent;
            this.context = Objects.requireNonNull(context, "context");
            this.chatLane = chatLane;
            if (enabledForChat() && settings.uiEnabled()) {
                chatSink.begin(context);
            }
        }

        public void append(String text) {
            if (closed || !enabledForChat() || text == null || text.isEmpty()) {
                return;
            }
            streamSeen = true;
            if (settings.uiEnabled()) {
                chatSink.append(context, text);
            }
            if (settings.ttsEnabled()) {
                ttsBuffer.append(text).forEach(this::speak);
            }
        }

        public void complete(String fullText) {
            if (closed) {
                return;
            }
            closed = true;
            if (!enabledForChat()) {
                return;
            }
            String text = fullText == null ? "" : fullText;
            if (!streamSeen && !text.isBlank()) {
                if (settings.uiEnabled()) {
                    chatSink.append(context, text);
                }
                if (settings.ttsEnabled()) {
                    ttsBuffer.append(text).forEach(this::speak);
                }
            }
            if (settings.ttsEnabled()) {
                ttsBuffer.flush().forEach(this::speak);
                endTtsSession();
            }
            if (settings.uiEnabled()) {
                chatSink.complete(context, text);
            }
        }

        public void fail(String reason) {
            if (closed) {
                return;
            }
            closed = true;
            ttsBuffer.clear();
            if (enabledForChat() && settings.ttsEnabled()) {
                endTtsSession();
            }
            if (enabledForChat() && settings.uiEnabled()) {
                chatSink.fail(context, reason == null ? "" : reason);
            }
        }

        private boolean enabledForChat() {
            return chatLane && settings.outputMode() != AXOutputMode.DISABLED;
        }

        private void speak(String sentence) {
            adapter.streamTtsSentence(parent, payload(sentence));
        }

        private void endTtsSession() {
            adapter.endTtsSession(parent, payload(""));
        }

        private TtsSpeakPayload payload(String sentence) {
            return new TtsSpeakPayload(
                    sentence,
                    context.ttsTurnId(),
                    context.ttsSessionId(),
                    TtsPlaybackPlacement.QUEUE_AFTER_SESSION,
                    TtsTextInputMode.SENTENCE_STREAM,
                    settings.ttsVoiceOptions()
            );
        }
    }
}
