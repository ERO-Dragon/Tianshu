package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntimeFailurePolicy;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSessionState;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSynthesisScheduler;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TtsPlaybackController {
    private static final String MODULE_ID = "module.tts";
    private static final String PLAYBACK_CONCURRENCY_KEY = MODULE_ID + ":playback";
    private static final Priority PLAYBACK_COMMAND_PRIORITY = Priority.NORMAL;

    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private final TtsPlaybackListener listener;
    private final ProtocolExecutorManager executorManager;
    private final Map<TtsSession, PlaybackSlot> slots = new LinkedHashMap<>();
    private long nextSequence;
    private PlaybackSlot activeSlot;
    private boolean bridgeTransitionPending;

    public TtsPlaybackController(IAudioBridge audioBridge, IGameEnvironment env, TtsPlaybackListener listener, ProtocolExecutorManager executorManager) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.listener = listener;
        this.executorManager = executorManager;
    }

    public synchronized boolean enqueue(TtsSession session) {
        if (session == null || session.isTerminal()) {
            return false;
        }
        slots.computeIfAbsent(session, ignored -> new PlaybackSlot(session, TtsSynthesisScheduler.priorityFor(session.request()), nextSequence++));
        activateNextIfPossible();
        return true;
    }

    public synchronized void removeQueued(TtsSession session) {
        if (session == null) {
            return;
        }
        PlaybackSlot slot = slots.get(session);
        if (slot == null || slot == activeSlot) {
            return;
        }
        slot.cancelled = true;
        slot.audio.clear();
        slots.remove(session);
        activateNextIfPossible();
    }

    public synchronized void begin(TtsSession session, int sampleRate) {
        PlaybackSlot slot = slots.get(session);
        if (slot == null || session.isTerminal()) {
            return;
        }
        slot.sampleRate = sampleRate;
        slot.ready = true;
        session.transition(TtsSessionState.PLAYING);
        activateNextIfPossible();
    }

    public synchronized boolean feed(TtsSession session, byte[] audio) {
        PlaybackSlot slot = slots.get(session);
        if (audio == null || audio.length == 0 || slot == null || slot.cancelled || slot.finishRequested || session.isTerminal()) {
            return false;
        }
        slot.audio.addLast(audio);
        if (slot == activeSlot) {
            schedulePump(slot);
        }
        return true;
    }

    public synchronized void finish(TtsSession session) {
        PlaybackSlot slot = slots.get(session);
        if (slot == null || slot.cancelled || session.isTerminal()) {
            return;
        }
        slot.finishRequested = true;
        session.transition(TtsSessionState.DRAINING);
        if (slot == activeSlot) {
            schedulePump(slot);
        }
    }

    public synchronized void stopActive(String reason) {
        if (activeSlot == null) {
            return;
        }
        cancelSlot(activeSlot, reason);
    }

    public synchronized void cancel(TtsSession session, String reason) {
        if (session == null) {
            return;
        }
        PlaybackSlot slot = slots.get(session);
        session.cancel(reason);
        if (slot == null) {
            return;
        }
        cancelSlot(slot, reason);
    }

    public synchronized void stopAll(String reason) {
        for (PlaybackSlot slot : slots.values()) {
            slot.session.cancel(reason);
            slot.cancelled = true;
            slot.audio.clear();
        }
        slots.clear();
        activeSlot = null;
        bridgeTransitionPending = true;
        submitStopCommand();
    }

    public synchronized boolean isBusy() {
        return !slots.isEmpty();
    }

    public synchronized TtsSession activeSession() {
        return activeSlot == null ? null : activeSlot.session;
    }

    public synchronized void clearIfActive(TtsSession session) {
        PlaybackSlot slot = slots.remove(session);
        if (slot == null || activeSlot != slot) {
            return;
        }
        slot.audio.clear();
        activeSlot = null;
        activateNextIfPossible();
    }

    private void cancelSlot(PlaybackSlot slot, String reason) {
        slot.session.cancel(reason);
        slot.cancelled = true;
        slot.audio.clear();
        slots.remove(slot.session);
        if (activeSlot != slot) {
            activateNextIfPossible();
            return;
        }
        activeSlot = null;
        bridgeTransitionPending = true;
        submitStopCommand();
    }

    private void activateNextIfPossible() {
        if (activeSlot != null || bridgeTransitionPending) {
            return;
        }
        slots.entrySet().removeIf(entry -> entry.getValue().cancelled || entry.getKey().isTerminal());
        PlaybackSlot next = null;
        for (PlaybackSlot candidate : slots.values()) {
            if (next == null
                    || candidate.admissionPriority.weight() > next.admissionPriority.weight()
                    || (candidate.admissionPriority == next.admissionPriority && candidate.sequence < next.sequence)) {
                next = candidate;
            }
        }
        if (next == null || !next.ready) {
            return;
        }
        activeSlot = next;
        schedulePump(next);
    }

    private void schedulePump(PlaybackSlot slot) {
        if (slot.pumpScheduled || slot.cancelled || activeSlot != slot) {
            return;
        }
        slot.pumpScheduled = true;
        ProtocolTaskState state = executorManager.submit(playbackTaskSpec(slot.session, "pump"), () -> pump(slot)).state();
        if (state == ProtocolTaskState.REJECTED) {
            slot.pumpScheduled = false;
            env.warn("TTS playback task was rejected");
        }
    }

    private void pump(PlaybackSlot slot) {
        while (true) {
            PlaybackCommand command;
            synchronized (this) {
                if (activeSlot != slot || slot.cancelled || slot.session.isTerminal()) {
                    slot.pumpScheduled = false;
                    return;
                }
                if (!slot.startIssued) {
                    slot.startIssued = true;
                    command = PlaybackCommand.start(slot.sampleRate);
                } else if (!slot.audio.isEmpty()) {
                    command = PlaybackCommand.feed(slot.audio.removeFirst());
                } else if (slot.finishRequested && !slot.finishIssued) {
                    slot.finishIssued = true;
                    command = PlaybackCommand.finish();
                } else {
                    slot.pumpScheduled = false;
                    return;
                }
            }
            try {
                execute(slot, command);
            } catch (Throwable throwable) {
                TtsRuntimeFailurePolicy.rethrowFatal(throwable);
                env.error("TTS playback command failed", throwable);
                synchronized (this) {
                    slot.pumpScheduled = false;
                    cancelSlot(slot, "playback command failed");
                }
                return;
            }
            if (command.type == PlaybackCommandType.FINISH) {
                synchronized (this) {
                    slot.pumpScheduled = false;
                }
                return;
            }
        }
    }

    private void execute(PlaybackSlot slot, PlaybackCommand command) {
        switch (command.type) {
            case START -> {
                audioBridge.setOnPlaybackFinished(() -> listener.onPlaybackFinished(slot.session));
                audioBridge.startTtsPlayback(command.sampleRate);
            }
            case FEED -> audioBridge.feedTtsAudio(command.audio);
            case FINISH -> audioBridge.finishTtsPlayback();
        }
    }

    private void submitStopCommand() {
        ProtocolTaskState state = executorManager.submit(playbackTaskSpec(null, "stop"), () -> {
            try {
                audioBridge.stopTtsPlayback();
            } catch (Throwable throwable) {
                TtsRuntimeFailurePolicy.rethrowFatal(throwable);
                env.error("TTS playback stop failed", throwable);
            } finally {
                synchronized (TtsPlaybackController.this) {
                    bridgeTransitionPending = false;
                    activateNextIfPossible();
                }
            }
        }).state();
        if (state == ProtocolTaskState.REJECTED) {
            bridgeTransitionPending = false;
            env.warn("TTS playback stop task was rejected");
            activateNextIfPossible();
        }
    }

    private ProtocolTaskSpec playbackTaskSpec(TtsSession session, String action) {
        return ProtocolTaskSpec.builder()
                .taskId("tts-playback:" + action + ":" + System.nanoTime())
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.AUDIO_IO)
                .envelopeId(session == null ? "" : session.request().envelopeId())
                .priority(PLAYBACK_COMMAND_PRIORITY)
                .concurrencyKey(PLAYBACK_CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(256)
                .build();
    }

    private static final class PlaybackSlot {
        private final TtsSession session;
        private final Priority admissionPriority;
        private final long sequence;
        private final ArrayDeque<byte[]> audio = new ArrayDeque<>();
        private int sampleRate;
        private boolean ready;
        private boolean startIssued;
        private boolean finishRequested;
        private boolean finishIssued;
        private boolean pumpScheduled;
        private boolean cancelled;

        private PlaybackSlot(TtsSession session, Priority admissionPriority, long sequence) {
            this.session = session;
            this.admissionPriority = admissionPriority;
            this.sequence = sequence;
        }
    }

    private enum PlaybackCommandType {
        START,
        FEED,
        FINISH
    }

    private static final class PlaybackCommand {
        private final PlaybackCommandType type;
        private final int sampleRate;
        private final byte[] audio;

        private PlaybackCommand(PlaybackCommandType type, int sampleRate, byte[] audio) {
            this.type = type;
            this.sampleRate = sampleRate;
            this.audio = audio;
        }

        private static PlaybackCommand start(int sampleRate) {
            return new PlaybackCommand(PlaybackCommandType.START, sampleRate, null);
        }

        private static PlaybackCommand feed(byte[] audio) {
            return new PlaybackCommand(PlaybackCommandType.FEED, 0, audio);
        }

        private static PlaybackCommand finish() {
            return new PlaybackCommand(PlaybackCommandType.FINISH, 0, null);
        }
    }
}
