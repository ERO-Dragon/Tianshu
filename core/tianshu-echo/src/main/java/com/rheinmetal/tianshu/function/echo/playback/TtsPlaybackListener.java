package com.rheinmetal.tianshu.function.echo.playback;

import com.rheinmetal.tianshu.function.echo.runtime.TtsSession;

public interface TtsPlaybackListener {
    void onPlaybackFinished(TtsSession session);
}
