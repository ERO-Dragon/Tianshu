package com.rheinmetal.tianshu.ir;

import java.util.List;

public final class IRParseResult {
    private final boolean ready;
    private final String rawText;
    private final String healedRawText;
    private final List<ParseUnit> units;

    public IRParseResult(boolean ready, String rawText, String healedRawText, List<ParseUnit> units) {
        this.ready = ready;
        this.rawText = rawText;
        this.healedRawText = healedRawText;
        this.units = units;
    }

    public boolean isReady() {
        return ready;
    }

    public String getRawText() {
        return rawText;
    }

    public String getHealedRawText() {
        return healedRawText;
    }

    public List<ParseUnit> getUnits() {
        return units;
    }

    public boolean hasUnits() {
        return !units.isEmpty();
    }
}
