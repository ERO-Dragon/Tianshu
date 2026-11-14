package com.rheinmetal.tianshu.core.runtime;

import java.util.Locale;

public final class InferenceResourcePolicy {
    private static final int MOSS_TTS_THREADS = 2;
    private static final int POCKET_TTS_THREADS = 2;

    private final int processors;

    private InferenceResourcePolicy(int processors) {
        this.processors = Math.max(1, processors);
    }

    public static InferenceResourcePolicy systemDefault() {
        return new InferenceResourcePolicy(Runtime.getRuntime().availableProcessors());
    }

    public static InferenceResourcePolicy fixedProcessors(int processors) {
        return new InferenceResourcePolicy(processors);
    }

    public int processors() {
        return processors;
    }

    public int embeddingGpuHelperThreads() {
        if (processors >= 12) {
            return 4;
        }
        if (processors >= 6) {
            return 3;
        }
        return 2;
    }

    public int sherpaAsrThreads(boolean streaming, String architecture, String modelName) {
        if (streaming) {
            return processors >= 6 ? 2 : 1;
        }
        if (isHeavyAsr(architecture, modelName)) {
            return smallModelThreadBudget(4);
        }
        return smallModelThreadBudget(3);
    }

    public int sherpaTtsThreads(String engineType) {
        return smallModelThreadBudget(4);
    }

    public int mossTtsThreads() {
        return MOSS_TTS_THREADS;
    }

    /**
     * Pocket 4L keeps the same two-thread budget as MOSS: it leaves CPU for the game loop while
     * still measuring below real time on the reference laptop. Four threads improve first audio and
     * streaming RTF, but that is an explicit benchmark configuration, not the product default.
     */
    public int pocketTtsThreads() {
        return POCKET_TTS_THREADS;
    }

    private int smallModelThreadBudget(int upperBound) {
        int threads;
        if (processors >= 12) {
            threads = 4;
        } else if (processors >= 6) {
            threads = 3;
        } else {
            threads = 2;
        }
        return Math.max(1, Math.min(upperBound, threads));
    }

    private boolean isHeavyAsr(String architecture, String modelName) {
        return matches(architecture, "whisper")
                || matches(architecture, "funasr-nano")
                || matches(architecture, "nemo")
                || matches(modelName, "large")
                || matches(modelName, "xlarge")
                || matches(modelName, "turbo");
    }

    private boolean matches(String value, String needle) {
        return value != null
                && needle != null
                && value.toLowerCase(Locale.ROOT).contains(needle.toLowerCase(Locale.ROOT));
    }
}
