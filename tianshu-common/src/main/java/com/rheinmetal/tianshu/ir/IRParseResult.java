package com.rheinmetal.tianshu.ir;

import java.util.List;

public final class IRParseResult {
    private final boolean ready;
    private final String rawText;
    private final List<ParseUnit> units;

    public IRParseResult(boolean ready, String rawText, List<ParseUnit> units) {
        this.ready = ready;
        this.rawText = rawText;
        this.units = units;
    }

    public boolean isReady() {
        return ready;
    }

    public String getRawText() {
        return rawText;
    }

    public List<ParseUnit> getUnits() {
        return units;
    }

    public boolean hasUnits() {
        return !units.isEmpty();
    }
}
