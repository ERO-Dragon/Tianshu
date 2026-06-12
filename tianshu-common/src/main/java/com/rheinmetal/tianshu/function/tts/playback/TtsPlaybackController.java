package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSessionState;
import com.rheinmetal.tianshu.protocol.Priority;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskState;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class TtsPlaybackController {
    private static final String MODULE_ID = "module.tts";
    private static final String PLAYBACK_CONCURRENCY_KEY = MODULE_ID + ":playback";

    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private final TtsPlaybackListener listener;
    private final ProtocolExecutorManager executorManager;
    private final Set<TtsSession> openSessions = new LinkedHashSet<>();
    private final Map<TtsSession, PlaybackSlot> slots = new HashMap<>();
    private TtsSession activeSession;

    public TtsPlaybackController(IAudioBridge audioBridge, IGameEnvironment env, TtsPlaybackListener listener, ProtocolExecutorManager executorManager) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.listener = listener;
        this.executorManager = executorManager;
    }

    public synchronized void begin(TtsSession session, int sampleRate) {
        if (session == null || session.isTerminal()) {
            return;
        }
        openSessions.add(session);
        slots.put(session, new PlaybackSlot(session, sampleRate));
        session.transition(TtsSessionState.PLAYING);
        submitPlaybackTask(session, "start", () -> {
            markActive(session);
            audioBridge.setOnPlaybackFinished(() -> listener.onPlaybackFinished(session));
            audioBridge.startTtsPlayback(sampleRate);
        });
    }

    public synchronized boolean feed(TtsSession session, byte[] audio) {
        if (audio == null || audio.length == 0 || !openSessions.contains(session) || session.isTerminal()) {
            return false;
        }
        PlaybackSlot slot = slots.get(session);
        if (slot == null) {
            return false;
        }
        return submitPlaybackTask(session, "feed", () -> feedOrDrop(session, audio));
    }

    public synchronized void finish(TtsSession session) {
        if (!openSessions.contains(session) || session.isTerminal()) {
            return;
        }
        session.transition(TtsSessionState.DRAINING);
        submitPlaybackTask(session, "finish", () -> finishOrDefer(session));
    }

    public synchronized void stopActive(String reason) {
        if (activeSession != null) {
            activeSession.cancel(reason);
            openSessions.remove(activeSession);
            removeSlot(activeSession);
        }
        activeSession = null;
        stopAudioBridge();
    }

    public synchronized void cancel(TtsSession session, String reason) {
        if (session == null) {
            return;
        }
        session.cancel(reason);
        openSessions.remove(session);
        removeSlot(session);
        if (activeSession == session) {
            activeSession = null;
            stopAudioBridge();
        }
    }

    public synchronized void stopAll(String reason) {
        for (TtsSession session : openSessions) {
            session.cancel(reason);
        }
        openSessions.clear();
        slots.clear();
        activeSession = null;
        stopAudioBridge();
    }

    private void stopAudioBridge() {
        try {
            audioBridge.stopTtsPlayback();
        } catch (Throwable t) {
            env.error("TTS playback stop failed", t);
        }
    }

    public synchronized boolean isBusy() {
        return !openSessions.isEmpty();
    }

    public synchronized TtsSession activeSession() {
        return activeSession;
    }

    public synchronized void clearIfActive(TtsSession session) {
        openSessions.remove(session);
        removeSlot(session);
        if (activeSession == session) {
            activeSession = null;
        }
    }

    private boolean submitPlaybackTask(TtsSession session, String action, Runnable task) {
        ProtocolTaskState state = executorManager.submit(playbackTaskSpec(session, action), () -> {
            if (!canRun(session)) {
                return;
            }
            task.run();
        }).state();
        return state != ProtocolTaskState.REJECTED;
    }

    private synchronized boolean canRun(TtsSession session) {
        if (session == null || session.isTerminal()) {
            openSessions.remove(session);
            if (activeSession == session) {
                activeSession = null;
            }
            return false;
        }
        return openSessions.contains(session);
    }

    private synchronized void markActive(TtsSession session) {
        if (openSessions.contains(session) && !session.isTerminal()) {
            activeSession = session;
        }
    }

    private void feedOrDrop(TtsSession session, byte[] audio) {
        synchronized (this) {
            PlaybackSlot slot = slots.get(session);
            if (slot == null || session.isTerminal() || !openSessions.contains(session)) {
                return;
            }
            if (activeSession != session) {
                return;
            }
        }
        audioBridge.feedTtsAudio(audio);
    }

    private void finishOrDefer(TtsSession session) {
        synchronized (this) {
            PlaybackSlot slot = slots.get(session);
            if (slot == null || session.isTerminal() || !openSessions.contains(session)) {
                return;
            }
            if (activeSession != session) {
                slot.finishRequested = true;
                return;
            }
            session.transition(TtsSessionState.DRAINING);
        }
        audioBridge.finishTtsPlayback();
    }

    private synchronized void removeSlot(TtsSession session) {
        slots.remove(session);
    }

    private ProtocolTaskSpec playbackTaskSpec(TtsSession session, String action) {
        return ProtocolTaskSpec.builder()
                .taskId("tts-playback:" + action + ":" + System.nanoTime())
                .moduleId(MODULE_ID)
                .lane(ExecutionLane.AUDIO_IO)
                .envelopeId(session == null ? "" : session.request().envelopeId())
                .priority(session == null ? Priority.NORMAL : session.request().priority())
                .concurrencyKey(PLAYBACK_CONCURRENCY_KEY)
                .maxConcurrency(1)
                .queueCapacity(256)
                .build();
    }

    private static final class PlaybackSlot {
        private final TtsSession session;
        private boolean finishRequested;

        private PlaybackSlot(TtsSession session, int sampleRate) {
            this.session = session;
        }
    }
}
