package com.rheinmetal.tianshu.function.tts.synthesis.moss;

final class MossRepeatedFrameException extends RuntimeException {
    final int consecutiveFrameCount;
    final int firstRepeatedFrameIndex;
    final int generatedFrameIndex;

    MossRepeatedFrameException(int consecutiveFrameCount, int firstRepeatedFrameIndex, int generatedFrameIndex) {
        super("TTS_MOSS_GENERATION_REPEATED "
                + "repeatedFrames="
                + consecutiveFrameCount
                + " times (firstRepeatedFrame=" + firstRepeatedFrameIndex
                + ", generatedFrame=" + generatedFrameIndex + ")");
        this.consecutiveFrameCount = consecutiveFrameCount;
        this.firstRepeatedFrameIndex = firstRepeatedFrameIndex;
        this.generatedFrameIndex = generatedFrameIndex;
    }
}
