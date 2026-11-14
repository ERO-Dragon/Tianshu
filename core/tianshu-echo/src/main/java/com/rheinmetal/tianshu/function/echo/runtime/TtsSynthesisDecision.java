package com.rheinmetal.tianshu.function.echo.runtime;

import com.rheinmetal.tianshu.function.echo.synthesis.TtsSynthesisMode;

record TtsSynthesisDecision(int sentenceCount, String text, TtsSynthesisMode mode) {
    TtsSynthesisDecision {
        sentenceCount = Math.max(0, sentenceCount);
        text = text == null ? "" : text;
        mode = mode == null ? TtsSynthesisMode.FULL : mode;
    }
}
