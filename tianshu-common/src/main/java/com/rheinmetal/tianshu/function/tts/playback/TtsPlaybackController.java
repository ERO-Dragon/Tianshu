package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.api.IAudioBridge;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;
import com.rheinmetal.tianshu.function.tts.runtime.TtsSessionState;

public final class TtsPlaybackController {
    private final IAudioBridge audioBridge;
    private final IGameEnvironment env;
    private final TtsPlaybackListener listener;
    private TtsSession activeSession;
    private boolean playbackOpen;

    public TtsPlaybackController(IAudioBridge audioBridge, IGameEnvironment env, TtsPlaybackListener listener) {
        this.audioBridge = audioBridge;
        this.env = env;
        this.listener = listener;
    }

    public synchronized void begin(TtsSession session, int sampleRate) {
        if (activeSession != null && activeSession != session) {
            stopActive("replaced");
        }
        activeSession = session;
        playbackOpen = true;
        session.transition(TtsSessionState.PLAYING);
        audioBridge.setOnPlaybackFinished(() -> listener.onPlaybackFinished(session));
        audioBridge.startTtsPlayback(sampleRate);
    }

    public synchronized void feed(TtsSession session, byte[] audio) {
        if (audio == null || audio.length == 0 || activeSession != session || !playbackOpen) {
            return;
        }
        audioBridge.feedTtsAudio(audio);
    }

    public synchronized void finish(TtsSession session) {
        if (activeSession != session || !playbackOpen) {
            return;
        }
        session.transition(TtsSessionState.DRAINING);
        playbackOpen = false;
        audioBridge.finishTtsPlayback();
    }

    public synchronized void stopActive(String reason) {
        if (activeSession != null) {
            activeSession.cancel(reason);
        }
        playbackOpen = false;
        activeSession = null;
        try {
            audioBridge.stopTtsPlayback();
        } catch (Throwable t) {
            env.error("TTS 播放停止失败", t);
        }
    }

    public synchronized boolean isBusy() {
        return activeSession != null;
    }

    public synchronized TtsSession activeSession() {
        return activeSession;
    }

    public synchronized void clearIfActive(TtsSession session) {
        if (activeSession == session) {
            activeSession = null;
            playbackOpen = false;
        }
    }
}
