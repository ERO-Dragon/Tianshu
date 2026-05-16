package com.rheinmetal.tianshu.function.asr.recognition;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.TriggerMode;
import com.rheinmetal.tianshu.function.asr.AsrProtocolAdapter;
import com.rheinmetal.tianshu.function.asr.engine.AsrEngine;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskHandle;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

public final class AsrRecognitionService {
    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final Supplier<AsrEngine> engineSupplier;
    private final AsrProtocolAdapter adapter;
    private volatile StreamingRuntime streamingRuntime;
    private volatile ProtocolTaskHandle streamingTask;
    private volatile ProtocolTaskHandle completeTask;

    public AsrRecognitionService(IGameEnvironment env, ITianshuConfig config, Supplier<AsrEngine> engineSupplier, AsrProtocolAdapter adapter) {
        this.env = env;
        this.config = config;
        this.engineSupplier = engineSupplier;
        this.adapter = adapter;
    }

    public void recognizeComplete(byte[] audioData, long sessionId, String inputMode, Consumer<AsrRecognitionResult> onResult, Runnable onComplete) {
        cancelCompleteTask();
        completeTask = adapter.submitRecognitionTask("complete", () -> {
            try {
                env.info("ASR 开始完整识别，音频长度=" + (audioData == null ? 0 : audioData.length) + " bytes");
                String result = engine().recognizeComplete(audioData);
                if (isMeaningfulText(result)) {
                    onResult.accept(new AsrRecognitionResult(result, result, sessionId, inputMode, false));
                } else {
                    env.info("ASR 完整识别结果为空");
                }
            } catch (Exception e) {
                env.error("ASR 完整识别失败", e);
            } finally {
                onComplete.run();
            }
        });
    }

    public boolean startStreaming(long sessionId, Consumer<AsrRecognitionResult> onResult) {
        StreamingRuntime current = streamingRuntime;
        if (current != null && current.accepts(sessionId)) {
            return true;
        }
        if (current != null) {
            stopStreaming();
        }
        AsrEngine.StreamingSession createdSession = engine().createStreamingSession();
        if (createdSession == null) {
            env.warn("ASR 流式识别 session 创建失败");
            return false;
        }
        StreamingRuntime runtime = new StreamingRuntime(sessionId, createdSession);
        streamingRuntime = runtime;
        streamingTask = adapter.submitRecognitionTask("stream", () -> processStreaming(runtime, onResult));
        return true;
    }

    public void acceptAudioChunk(byte[] chunk, long sessionId) {
        if (chunk == null || chunk.length == 0) {
            return;
        }
        StreamingRuntime runtime = streamingRuntime;
        if (runtime != null && runtime.accepts(sessionId)) {
            runtime.offer(StreamCommand.audio(chunk));
        }
    }

    public void forceFlush(long sessionId) {
        StreamingRuntime runtime = streamingRuntime;
        if (runtime != null && runtime.accepts(sessionId)) {
            runtime.offer(StreamCommand.flush());
        }
    }

    public void stopStreaming() {
        StreamingRuntime runtime = streamingRuntime;
        if (runtime == null) {
            return;
        }
        runtime.close();
        runtime.offer(StreamCommand.stop());
        if (streamingRuntime == runtime) {
            streamingRuntime = null;
        }
        ProtocolTaskHandle task = streamingTask;
        streamingTask = null;
        if (task != null && !task.isDone() && !task.isRunning()) {
            task.cancel("ASR stream stopped");
            releaseStreamingSession(runtime);
        }
    }

    public void stopAll() {
        cancelCompleteTask();
        stopStreaming();
    }

    public boolean isStreaming() {
        StreamingRuntime runtime = streamingRuntime;
        return runtime != null && runtime.open();
    }

    private void processStreaming(StreamingRuntime runtime, Consumer<AsrRecognitionResult> onResult) {
        try {
            while (isCurrentRuntime(runtime)) {
                StreamCommand command = runtime.take();
                if (command.type() == StreamCommandType.STOP) {
                    break;
                }
                if (command.type() == StreamCommandType.FLUSH) {
                    publishStreamingResult(engine().forceFlush(runtime.engineSession()), runtime, "force_flush", onResult);
                    continue;
                }
                String text = engine().feedAudio(runtime.engineSession(), command.audio());
                if (engine().isEndpoint(runtime.engineSession())) {
                    publishStreamingResult(text, runtime, "stream", onResult);
                    engine().reset(runtime.engineSession());
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            env.error("ASR 流式识别失败", e);
        } finally {
            releaseStreamingSession(runtime);
        }
    }

    private void publishStreamingResult(String text, StreamingRuntime runtime, String inputMode, Consumer<AsrRecognitionResult> onResult) {
        if (!isCurrentRuntime(runtime)) {
            return;
        }
        AsrRecognitionResult result = normalizeStreamingText(text, runtime.sessionId(), inputMode);
        if (result != null && result.hasText() && isCurrentRuntime(runtime)) {
            onResult.accept(result);
        }
    }

    private AsrRecognitionResult normalizeStreamingText(String text, long sessionId, String inputMode) {
        if (!isMeaningfulText(text)) {
            return null;
        }
        if (config.getTriggerMode() != TriggerMode.WAKE_WORD) {
            return new AsrRecognitionResult(text, text, sessionId, inputMode, false);
        }
        String wakeWord = config.getWakeWord();
        if (wakeWord == null || wakeWord.isBlank() || !text.contains(wakeWord)) {
            env.info("ASR 断句完成，未命中唤醒词: " + wakeWord);
            return null;
        }
        int index = text.indexOf(wakeWord) + wakeWord.length();
        String realCommand = text.substring(index).trim();
        if (!isMeaningfulText(realCommand)) {
            env.info("ASR 命中唤醒词，但唤醒词后无有效命令");
            return null;
        }
        return new AsrRecognitionResult(realCommand, text, sessionId, inputMode, true);
    }

    private void cancelCompleteTask() {
        ProtocolTaskHandle task = completeTask;
        if (task != null && !task.isDone()) {
            task.cancel("ASR complete recognition cancelled");
        }
        completeTask = null;
    }

    private boolean isCurrentRuntime(StreamingRuntime runtime) {
        return runtime != null && runtime.open() && streamingRuntime == runtime;
    }

    private void releaseStreamingSession(StreamingRuntime runtime) {
        if (runtime == null || !runtime.release()) {
            return;
        }
        if (streamingRuntime == runtime) {
            streamingRuntime = null;
        }
        try {
            engine().releaseStreamingSession(runtime.engineSession());
        } catch (Exception e) {
            env.error("释放 ASR 流式 session 失败", e);
        }
    }

    private AsrEngine engine() {
        AsrEngine engine = engineSupplier.get();
        if (engine == null) {
            throw new IllegalStateException("ASR engine is not ready");
        }
        return engine;
    }

    private boolean isMeaningfulText(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        String cleanText = text.replaceAll("[\\p{P}\\s\\p{C}]", "");
        return cleanText.length() >= 1;
    }

    private enum StreamCommandType {
        AUDIO,
        FLUSH,
        STOP
    }

    private record StreamCommand(StreamCommandType type, byte[] audio) {
        static StreamCommand audio(byte[] audio) {
            return new StreamCommand(StreamCommandType.AUDIO, audio);
        }

        static StreamCommand flush() {
            return new StreamCommand(StreamCommandType.FLUSH, null);
        }

        static StreamCommand stop() {
            return new StreamCommand(StreamCommandType.STOP, null);
        }
    }

    private static final class StreamingRuntime {
        private final long sessionId;
        private final AsrEngine.StreamingSession engineSession;
        private final BlockingQueue<StreamCommand> commands = new LinkedBlockingQueue<>();
        private final AtomicBoolean open = new AtomicBoolean(true);
        private final AtomicBoolean released = new AtomicBoolean(false);

        private StreamingRuntime(long sessionId, AsrEngine.StreamingSession engineSession) {
            this.sessionId = sessionId;
            this.engineSession = engineSession;
        }

        private boolean accepts(long sessionId) {
            return open() && this.sessionId == sessionId;
        }

        private boolean open() {
            return open.get();
        }

        private long sessionId() {
            return sessionId;
        }

        private AsrEngine.StreamingSession engineSession() {
            return engineSession;
        }

        private void close() {
            open.set(false);
        }

        private boolean release() {
            close();
            return released.compareAndSet(false, true);
        }

        private void offer(StreamCommand command) {
            try {
                commands.put(command);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        private StreamCommand take() throws InterruptedException {
            return commands.take();
        }
    }
}
