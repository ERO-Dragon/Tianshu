package com.rheinmetal.tianshu.dubhe.core.output;

import com.rheinmetal.tianshu.dubhe.DubheProtocolAdapter;
import com.rheinmetal.tianshu.protocol.TianshuEnvelope;
import com.rheinmetal.tianshu.dubhe.speech.DubheSpeechSink;
import com.rheinmetal.tianshu.dubhe.speech.DubheSpeechUtterance;

import java.util.Objects;

public final class DubheOutputProcessor {
    private final DubheProtocolAdapter adapter;
    private final DubheOutputSettings settings;
    private final DubheChatOutputSink chatSink;
    private final DubheReplyTextRepository replyTexts;
    private final DubheSpeechSink speechSink;

    public DubheOutputProcessor(
            DubheProtocolAdapter adapter,
            DubheOutputSettings settings,
            DubheChatOutputSink chatSink,
            DubheReplyTextRepository replyTexts
    ) {
        this(adapter, settings, chatSink, replyTexts, DubheSpeechSink.NONE);
    }

    public DubheOutputProcessor(
            DubheProtocolAdapter adapter,
            DubheOutputSettings settings,
            DubheChatOutputSink chatSink,
            DubheReplyTextRepository replyTexts,
            DubheSpeechSink speechSink
    ) {
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.settings = settings == null ? DubheOutputSettings.DEFAULT : settings;
        this.chatSink = chatSink == null ? DubheChatOutputSink.NOOP : chatSink;
        this.replyTexts = Objects.requireNonNull(replyTexts, "replyTexts");
        this.speechSink = speechSink == null ? DubheSpeechSink.NONE : speechSink;
    }

    /**
     * 模型这一轮没有任何可念的正文（只输出空白或只输出委派标签）时，唯一允许说出口的那句话。
     *
     * <p>措辞在 {@code dubhe_reply_texts.json}（zh_cn / en_us），这里只解析资源键——输出层是最后一道"什么
     * 会变成玩家听到的声音"的关口，兜底句因此归它，而不是散落在调用方。</p>
     */
    public String emptyAnswerText() {
        return replyTexts.text(DubheReplyTextRepository.KEY_EMPTY_ANSWER);
    }

    public DubheOutputTurn startTurn(TianshuEnvelope parent, DubheOutputContext context, boolean chatLane) {
        return startTurn(parent, context, chatLane, () -> { });
    }

    public DubheOutputTurn startTurn(TianshuEnvelope parent, DubheOutputContext context, boolean chatLane, Runnable firstTtsSubmission) {
        return new DubheOutputTurn(parent, context, chatLane, firstTtsSubmission);
    }

    public void interrupt(String reasonCode) {
        if (settings.ttsEnabled()) {
            speechSink.stopSource(reasonCode);
        }
    }

    public final class DubheOutputTurn {
        private final TianshuEnvelope parent;
        private final DubheOutputContext context;
        private final boolean chatLane;
        private final Runnable firstTtsSubmission;
        private final DubheSentenceBuffer ttsBuffer = new DubheSentenceBuffer();
        private boolean streamSeen;
        private boolean closed;

        private DubheOutputTurn(TianshuEnvelope parent, DubheOutputContext context, boolean chatLane, Runnable firstTtsSubmission) {
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
            return chatLane && settings.outputMode() != DubheOutputMode.DISABLED;
        }

        private void speak(String sentence) {
            firstTtsSubmission.run();
            speechSink.speak(DubheSpeechUtterance.streamChunk(
                    sentence, context.ttsTurnId(), context.ttsSessionId(), settings.voiceProfile()));
        }

        private void endTtsSession() {
            speechSink.speak(DubheSpeechUtterance.streamEnd(
                    context.ttsTurnId(), context.ttsSessionId(), settings.voiceProfile()));
        }
    }
}
