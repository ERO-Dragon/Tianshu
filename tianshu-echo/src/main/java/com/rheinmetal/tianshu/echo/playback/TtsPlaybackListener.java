package com.rheinmetal.tianshu.echo.playback;

import com.rheinmetal.tianshu.echo.runtime.TtsSession;

public interface TtsPlaybackListener {
    void onPlaybackFinished(TtsSession session);
}
