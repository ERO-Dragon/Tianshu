package com.rheinmetal.tianshu.function.ir.core;

public final class ParseUnit {
    public final Intent intent;
    public final String targetRealItemId;
    public final boolean isNegated;

    public ParseUnit(Intent intent, String targetRealItemId, boolean isNegated) {
        this.intent = intent;
        this.targetRealItemId = targetRealItemId;
        this.isNegated = isNegated;
    }

    @Override
    public String toString() {
        return "ParseUnit{intent=" + intent + ", target='" + targetRealItemId + "', negated=" + isNegated + "}";
    }
}
