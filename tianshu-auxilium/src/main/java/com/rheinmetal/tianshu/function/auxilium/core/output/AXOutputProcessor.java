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
    private final AuxiliumOutputSettings settings;
    private final AXChatOutputSink chatSink;
    private final AXReplyTextRepository replyTexts;

    public AXOutputProcessor(
            AXProtocolAdapter adapter,
            AuxiliumOutputSettings settings,
            AXChatOutputSink chatSink,
            AXReplyTextRepository replyTexts
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.settings = settings == null ? AuxiliumOutputSettings.DEFAULT : settings;
        this.chatSink = chatSink == null ? AXChatOutputSink.NOOP : chatSink;
        this.replyTexts = Objects.requireNonNull(replyTexts, "replyTexts");
    }

    /**
     * 模型这一轮没有任何可念的正文（只输出空白或只输出委派标签）时，唯一允许说出口的那句话。
     *
     * <p>措辞在 {@code ax_reply_texts.json}（zh_cn / en_us），这里只解析资源键——输出层是最后一道"什么
     * 会变成玩家听到的声音"的关口，兜底句因此归它，而不是散落在调用方。</p>
     */
    public String emptyAnswerText() {
        return replyTexts.text(AXReplyTextRepository.KEY_EMPTY_ANSWER);
    }

    public AXOutputTurn startTurn(TianshuEnvelope parent, AXOutputContext context, boolean chatLane) {
        return startTurn(parent, context, chatLane, () -> { });
    }

    public AXOutputTurn startTurn(TianshuEnvelope parent, AXOutputContext context, boolean chatLane, Runnable firstTtsSubmission) {
        return new AXOutputTurn(parent, context, chatLane, firstTtsSubmission);
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
        private final Runnable firstTtsSubmission;
        private final AXSentenceBuffer ttsBuffer = new AXSentenceBuffer();
        private boolean streamSeen;
        private boolean closed;

        private AXOutputTurn(TianshuEnvelope parent, AXOutputContext context, boolean chatLane, Runnable firstTtsSubmission) {
            this.parent = parent;
            this.context = Objects.requireNonNull(context, "context");
            this.chatLane = chatLane;
            this.firstTtsSubmission = firstTtsSubmission == null ? () -> { } : firstTtsSubmission;
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
            return chatLane && settings.outputMode() != AuxiliumOutputMode.DISABLED;
        }

        private void speak(String sentence) {
            firstTtsSubmission.run();
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
