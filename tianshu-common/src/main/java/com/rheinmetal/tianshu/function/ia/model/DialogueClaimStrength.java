package com.rheinmetal.tianshu.function.ia.model;

public enum DialogueClaimStrength {
    NORMAL(0.7D),
    STRONG(1.0D);

    private final double attention;

    DialogueClaimStrength(double attention) {
        this.attention = attention;
    }

    public double attention() {
        return attention;
    }
}
