package com.rheinmetal.tianshu.echo.synthesis;

/** Describes whether a synthesis sink is receiving playback chunks or one complete handoff. */
public enum TtsAudioDelivery {
    PLAYBACK,
    COMPLETE_RESULT
}
