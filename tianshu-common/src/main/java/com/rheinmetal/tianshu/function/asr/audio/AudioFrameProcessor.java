package com.rheinmetal.tianshu.function.asr.audio;

import java.util.List;

@FunctionalInterface
public interface AudioFrameProcessor {
    byte[] process(byte[] audio);

    default void reset() {
    }

    default AudioFrameProcessor then(AudioFrameProcessor next) {
        if (next == null) {
            return this;
        }
        AudioFrameProcessor current = this;
        return new AudioFrameProcessor() {
            @Override
            public byte[] process(byte[] audio) {
                return next.process(current.process(audio));
            }

            @Override
            public void reset() {
                current.reset();
                next.reset();
            }
        };
    }

    static AudioFrameProcessor identity() {
        return audio -> audio;
    }

    static AudioFrameProcessor chain(List<AudioFrameProcessor> processors) {
        if (processors == null || processors.isEmpty()) {
            return identity();
        }
        AudioFrameProcessor chained = identity();
        for (AudioFrameProcessor processor : processors) {
            if (processor != null) {
                chained = chained.then(processor);
            }
        }
        return chained;
    }
}
