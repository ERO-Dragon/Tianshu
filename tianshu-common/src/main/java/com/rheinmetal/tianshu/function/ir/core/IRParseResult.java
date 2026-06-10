package com.rheinmetal.tianshu.function.ir.core;

import java.util.List;

public final class IRParseResult {
    private final boolean ready;
    private final String rawText;
    private final String healedRawText;
    private final List<ParseUnit> units;
    private final List<String> matchedItemRealIds;
    private final List<String> matchedEntityTypeIds;
    private final String bestCandidateText;
    private final String bestCandidateRealItemId;
    private final double bestScore;
    private final double entityRatio;
    private final double interceptThreshold;
    private final String candidateIntentType;

    public IRParseResult(boolean ready, String rawText, String healedRawText, List<ParseUnit> units) {
        this(ready, rawText, healedRawText, units, List.of(), List.of(), "", "", 0.0D, 0.0D, Double.MAX_VALUE, "");
    }

    public IRParseResult(boolean ready, String rawText, String healedRawText, List<ParseUnit> units, String bestCandidateText, String bestCandidateRealItemId, double bestScore, double entityRatio, double interceptThreshold, String candidateIntentType) {
        this(ready, rawText, healedRawText, units, List.of(), List.of(), bestCandidateText, bestCandidateRealItemId, bestScore, entityRatio, interceptThreshold, candidateIntentType);
    }

    public IRParseResult(boolean ready, String rawText, String healedRawText, List<ParseUnit> units, List<String> matchedItemRealIds, String bestCandidateText, String bestCandidateRealItemId, double bestScore, double entityRatio, double interceptThreshold, String candidateIntentType) {
        this(ready, rawText, healedRawText, units, matchedItemRealIds, List.of(), bestCandidateText, bestCandidateRealItemId, bestScore, entityRatio, interceptThreshold, candidateIntentType);
    }

    public IRParseResult(boolean ready, String rawText, String healedRawText, List<ParseUnit> units, List<String> matchedItemRealIds, List<String> matchedEntityTypeIds, String bestCandidateText, String bestCandidateRealItemId, double bestScore, double entityRatio, double interceptThreshold, String candidateIntentType) {
        this.ready = ready;
        this.rawText = rawText;
        this.healedRawText = healedRawText;
        this.units = units == null ? List.of() : units;
        this.matchedItemRealIds = matchedItemRealIds == null ? List.of() : List.copyOf(matchedItemRealIds);
        this.matchedEntityTypeIds = matchedEntityTypeIds == null ? List.of() : List.copyOf(matchedEntityTypeIds);
        this.bestCandidateText = bestCandidateText == null ? "" : bestCandidateText;
        this.bestCandidateRealItemId = bestCandidateRealItemId == null ? "" : bestCandidateRealItemId;
        this.bestScore = bestScore;
        this.entityRatio = entityRatio;
        this.interceptThreshold = interceptThreshold;
        this.candidateIntentType = candidateIntentType == null ? "" : candidateIntentType;
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

    public List<String> getMatchedItemRealIds() {
        return matchedItemRealIds;
    }

    public List<String> getMatchedEntityTypeIds() {
        return matchedEntityTypeIds;
    }

    public String getBestCandidateText() {
        return bestCandidateText;
    }

    public String getBestCandidateRealItemId() {
        return bestCandidateRealItemId;
    }

    public double getBestScore() {
        return bestScore;
    }

    public double getEntityRatio() {
        return entityRatio;
    }

    public double getInterceptThreshold() {
        return interceptThreshold;
    }

    public String getCandidateIntentType() {
        return candidateIntentType;
    }

    public boolean shouldRequestLlmReview() {
        if (!ready || hasUnits() || bestCandidateText.isBlank()) {
            return false;
        }
        if (entityRatio <= 0.2D) {
            return false;
        }
        double reviewThreshold = interceptThreshold == Double.MAX_VALUE ? 0.25D : Math.max(0.15D, interceptThreshold * 0.6D);
        return bestScore >= reviewThreshold;
    }
}
