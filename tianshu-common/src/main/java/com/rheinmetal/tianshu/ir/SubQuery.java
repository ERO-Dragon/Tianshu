package com.rheinmetal.tianshu.ir;

final class SubQuery {
    final String rawChunk;
    final boolean negFlag;
    final Intent intent;

    SubQuery(String rawChunk, boolean negFlag, Intent intent) {
        this.rawChunk = rawChunk;
        this.negFlag = negFlag;
        this.intent = intent;
    }
}
