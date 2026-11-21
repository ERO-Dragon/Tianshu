package com.rheinmetal.tianshu.echo.synthesis.moss;

final class MossGenerationCancelledException extends Exception {
    MossGenerationCancelledException() {
        super("TTS_MOSS_GENERATION_CANCELLED");
    }
}
