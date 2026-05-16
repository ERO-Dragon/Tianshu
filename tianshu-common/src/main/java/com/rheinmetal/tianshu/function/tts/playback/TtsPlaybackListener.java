package com.rheinmetal.tianshu.function.tts.playback;

import com.rheinmetal.tianshu.function.tts.runtime.TtsSession;

public interface TtsPlaybackListener {
    void onPlaybackFinished(TtsSession session);
}
