package com.rheinmetal.tianshu.protocol.dialogue.model;

public enum DialogueAttentionDecay {
    FAST(0.06D),
    SLOW(0.02D);

    private final double perSecond;

    DialogueAttentionDecay(double perSecond) {
        this.perSecond = perSecond;
    }

    public double perSecond() {
        return perSecond;
    }
}
