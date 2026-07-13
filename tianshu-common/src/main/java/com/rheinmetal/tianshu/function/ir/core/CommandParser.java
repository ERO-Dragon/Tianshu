package com.rheinmetal.tianshu.function.ir.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Coordinates command-fragment parsing without owning retrieval or text-repair mechanics. */
public final class CommandParser {
    private static final double DIRECT_PARSE_MIN_SCORE = 0.15D;
    private static final double DIRECT_PARSE_MIN_LEAD = 0.1D;

    private final String[] prioritySplitters;
    private final String[] parallelSplitters;
    private final String[] negations;
    private final String[] fillerWords;
    private final String[] entityBoundaryWords;
    private final CommandParserPolicy policy;
    private final CommandCandidateRanker candidateRanker;
    private final CommandTextRepairer textRepairer;

    public CommandParser() {
        this(CommandParserPolicy.DEFAULT);
    }

    CommandParser(CommandParserPolicy policy) {
        Map<String, String[]> keywords = IntentKeywordLoader.load();
        this.prioritySplitters = keywords.getOrDefault("PRIORITY_SPLITTERS", new String[0]);
        this.parallelSplitters = keywords.getOrDefault("PARALLEL_SPLITTERS", new String[0]);
        this.negations = keywords.getOrDefault("NEGATIONS", new String[0]);
        this.fillerWords = keywords.getOrDefault("FILLER_WORDS", new String[0]);
        this.entityBoundaryWords = IntentKeywordLoader.getEntityBoundaryWords();
        this.policy = policy == null ? CommandParserPolicy.DEFAULT : policy;
        this.candidateRanker = new CommandCandidateRanker(this.policy);
        this.textRepairer = new CommandTextRepairer(this.policy);
    }

    public IRParseResult parse(String rawText, Set<Integer> contextInternalIds, boolean isFastIR) {
        if (rawText == null || rawText.isBlank()) {
            return new IRParseResult(false, rawText, rawText, List.of());
        }

        List<SubQuery> subQueries = splitToSubQueries(rawText);
        if (subQueries.isEmpty()) {
            return new IRParseResult(true, rawText, rawText, List.of());
        }

        List<ParseUnit> results = new ArrayList<>(subQueries.size());
        CommandCandidateRanker.LcsWorkspace sharedLcsWorkspace = new CommandCandidateRanker.LcsWorkspace();
        for (SubQuery subQuery : subQueries) {
            ParseUnit unit = parseSingleSubQuery(subQuery, contextInternalIds, sharedLcsWorkspace);
            if (unit != null) {
                results.add(unit);
            }
        }

        String healedRawText = rawText;
        List<String> matchedItemRealIds = List.of();
        List<String> matchedEntityTypeIds = List.of();
        ReviewHint reviewHint = ReviewHint.EMPTY;
        if (results.isEmpty()) {
            if (isFastIR) {
                FastIRFallbackResult fallback = processFastIRFallback(rawText, contextInternalIds, sharedLcsWorkspace);
                healedRawText = fallback.healedText;
                matchedItemRealIds = fallback.matchedItemRealIds;
                matchedEntityTypeIds = fallback.matchedEntityTypeIds;
                reviewHint = fallback.reviewHint;
                if (fallback.interceptedUnit != null) {
                    results.add(fallback.interceptedUnit);
                }
            } else {
                CommandCandidateRanker.ScoredCandidate best = findBestCandidateForIntercept(rawText, contextInternalIds, sharedLcsWorkspace);
                if (isItemCandidate(best) && best.score >= policy.finalIrFixedThreshold()) {
                    results.add(new ParseUnit(Intent.UNKNOWN, IRObjectId.raw(IRBaseUtils.reverseLookupArray[best.internalId]), false));
                }
            }
        } else if (isFastIR) {
            CommandTextRepairer.RepairResult repairResult = textRepairer.repair(
                    rawText,
                    executeRankQuery(rawText, contextInternalIds, sharedLcsWorkspace)
            );
            healedRawText = repairResult.text;
            matchedItemRealIds = repairResult.matchedItemRealIds;
            matchedEntityTypeIds = repairResult.matchedEntityTypeIds;
        }

        if (!results.isEmpty()) {
            LinkedHashSet<String> mergedIds = new LinkedHashSet<>(matchedItemRealIds);
            for (ParseUnit unit : results) {
                if (unit != null && unit.targetRealItemId != null && !unit.targetRealItemId.isBlank()) {
                    mergedIds.add(unit.targetRealItemId);
                }
            }
            matchedItemRealIds = List.copyOf(mergedIds);
        }

        return new IRParseResult(
                true,
                rawText,
                healedRawText,
                results,
                matchedItemRealIds,
                matchedEntityTypeIds,
                reviewHint.bestCandidateText,
                IRObjectId.raw(reviewHint.bestCandidateRealItemId),
                reviewHint.bestScore,
                reviewHint.entityRatio,
                reviewHint.interceptThreshold,
                reviewHint.candidateIntentType
        );
    }

    private FastIRFallbackResult processFastIRFallback(
            String rawText,
            Set<Integer> contextInternalIds,
            CommandCandidateRanker.LcsWorkspace lcsWorkspace
    ) {
        CommandCandidateRanker.ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);
        ParseUnit interceptedUnit = null;
        ReviewHint reviewHint = ReviewHint.EMPTY;
        if (ranked.length > 0) {
            CommandCandidateRanker.ScoredCandidate best = ranked[0];
            String targetText = IRBaseUtils.localizedNameArray[best.internalId];
            String targetObjectId = IRBaseUtils.reverseLookupArray[best.internalId];
            double threshold = computeInterceptThreshold(rawText, best);
            double entityRatio = computeEntityRatio(rawText, best);
            reviewHint = new ReviewHint(targetText, targetObjectId, best.score, entityRatio, threshold, Intent.UNKNOWN.name());
            if (isItemCandidate(best)
                    && best.score >= threshold
                    && !isGenericContainedQuery(IRBaseUtils.tokenize(rawText), ranked)) {
                interceptedUnit = new ParseUnit(Intent.UNKNOWN, IRObjectId.raw(targetObjectId), false);
            }
        }

        CommandTextRepairer.RepairResult repairResult = textRepairer.repair(rawText, ranked);
        return new FastIRFallbackResult(
                interceptedUnit,
                repairResult.text,
                repairResult.matchedItemRealIds,
                repairResult.matchedEntityTypeIds,
                reviewHint
        );
    }

    private CommandCandidateRanker.ScoredCandidate[] executeRankQuery(
            String rawText,
            Set<Integer> contextInternalIds,
            CommandCandidateRanker.LcsWorkspace lcsWorkspace
    ) {
        return candidateRanker.rank(rawText, contextInternalIds, lcsWorkspace);
    }

    private double computeInterceptThreshold(String rawText, CommandCandidateRanker.ScoredCandidate best) {
        String target = IRBaseUtils.localizedNameArray[best.internalId];
        if (target == null || target.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double entityRatio = computeEntityRatio(rawText, best);
        if (entityRatio >= policy.fastIrInterceptHighRatio()) {
            return policy.fastIrInterceptHighThreshold();
        }
        if (entityRatio > policy.fastIrInterceptMidRatioLow()) {
            return policy.fastIrInterceptMidThreshold();
        }
        return Double.MAX_VALUE;
    }

    private double computeEntityRatio(String rawText, CommandCandidateRanker.ScoredCandidate best) {
        String target = IRBaseUtils.localizedNameArray[best.internalId];
        if (target == null || target.isEmpty() || rawText == null || rawText.isEmpty()) {
            return 0.0D;
        }
        return (double) target.length() / rawText.length();
    }

    private CommandCandidateRanker.ScoredCandidate findBestCandidateForIntercept(
            String rawText,
            Set<Integer> contextInternalIds,
            CommandCandidateRanker.LcsWorkspace lcsWorkspace
    ) {
        CommandCandidateRanker.ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);
        return ranked.length == 0 ? null : ranked[0];
    }

    private ParseUnit parseSingleSubQuery(
            SubQuery subQuery,
            Set<Integer> contextInternalIds,
            CommandCandidateRanker.LcsWorkspace lcsWorkspace
    ) {
        String[] tokens = IRBaseUtils.tokenize(subQuery.rawChunk);
        CommandCandidateRanker.ScoredCandidate[] ranked = candidateRanker.rank(tokens, contextInternalIds, lcsWorkspace);
        if (ranked.length == 0) {
            return null;
        }

        CommandCandidateRanker.ScoredCandidate topCandidate = ranked[0];
        CommandCandidateRanker.ScoredCandidate nextCandidate = ranked.length > 1 ? ranked[1] : null;
        if (topCandidate.score < DIRECT_PARSE_MIN_SCORE
                || (nextCandidate != null && topCandidate.score - nextCandidate.score < DIRECT_PARSE_MIN_LEAD)
                || isGenericContainedQuery(tokens, ranked)) {
            return null;
        }

        CommandCandidateRanker.ScoredCandidate selected = textRepairer.selectCandidateByRepairEvidence(
                subQuery.rawChunk,
                ranked,
                topCandidate
        );
        if (!isItemCandidate(selected)) {
            return null;
        }
        return new ParseUnit(subQuery.intent, IRObjectId.raw(IRBaseUtils.reverseLookupArray[selected.internalId]), subQuery.negFlag);
    }

    private boolean isItemCandidate(CommandCandidateRanker.ScoredCandidate candidate) {
        return candidate != null && IRObjectId.isItem(IRBaseUtils.reverseLookupArray[candidate.internalId]);
    }

    private boolean isGenericContainedQuery(String[] queryTokens, CommandCandidateRanker.ScoredCandidate[] ranked) {
        if (queryTokens.length == 0) {
            return false;
        }
        String queryJoined = IRBaseUtils.joinTokens(queryTokens);
        int containedLongerCandidates = 0;
        for (CommandCandidateRanker.ScoredCandidate candidate : ranked) {
            String[] candidateTokens = IRBaseUtils.primaryAliasTokensArray[candidate.internalId];
            if (candidateTokens == null || candidateTokens.length == 0) {
                continue;
            }
            String candidateJoined = IRBaseUtils.joinTokens(candidateTokens);
            if (!candidateJoined.contains(queryJoined)) {
                continue;
            }
            if (candidateTokens.length == queryTokens.length) {
                return false;
            }
            if (candidateTokens.length > queryTokens.length && ++containedLongerCandidates >= 2) {
                return true;
            }
        }
        return false;
    }

    private List<SubQuery> splitToSubQueries(String rawText) {
        List<String> primaryParts = splitByKeywords(rawText, prioritySplitters);
        List<SubQuery> subQueries = new ArrayList<>();
        for (String part : primaryParts) {
            String normalizedPart = normalizeChunk(part);
            if (normalizedPart.isEmpty()) {
                continue;
            }
            boolean parentNeg = containsAny(normalizedPart, negations);
            for (String secondaryPart : splitByKeywords(normalizedPart, parallelSplitters)) {
                String normalized = normalizeChunk(secondaryPart);
                if (normalized.isEmpty()) {
                    continue;
                }
                String entityChunk = extractEntityChunk(normalized);
                if (!entityChunk.isEmpty()) {
                    subQueries.add(new SubQuery(entityChunk, parentNeg || containsAny(normalized, negations), Intent.UNKNOWN));
                }
            }
        }
        return subQueries;
    }

    private List<String> splitByKeywords(String rawText, String[] splitters) {
        List<String> parts = new ArrayList<>();
        parts.add(rawText);
        for (String splitter : splitters) {
            List<String> next = new ArrayList<>();
            for (String part : parts) {
                int start = 0;
                int index;
                while ((index = part.indexOf(splitter, start)) >= 0) {
                    String prefix = part.substring(start, index).trim();
                    if (!prefix.isEmpty()) {
                        next.add(prefix);
                    }
                    start = index + splitter.length();
                }
                String suffix = part.substring(start).trim();
                if (!suffix.isEmpty()) {
                    next.add(suffix);
                }
            }
            parts = next;
        }
        return parts;
    }

    private String normalizeChunk(String chunk) {
        String result = chunk;
        for (String filler : fillerWords) {
            result = result.replace(filler, " ");
        }
        return result.replace('，', ' ').replace(',', ' ').trim();
    }

    private String extractEntityChunk(String text) {
        String result = stripKeywords(text, negations);
        result = stripBoundaryKeywords(result, entityBoundaryWords);
        return stripKeywords(result, fillerWords).trim();
    }

    private String stripKeywords(String text, String[] keywords) {
        String result = text;
        for (String keyword : keywords) {
            result = result.replace(keyword, " ");
        }
        return result;
    }

    private String stripBoundaryKeywords(String text, String[] keywords) {
        String result = text;
        boolean changed = true;
        while (changed) {
            changed = false;
            for (String keyword : keywords) {
                if (keyword.isEmpty()) {
                    continue;
                }
                if (result.startsWith(keyword)) {
                    result = result.substring(keyword.length()).trim();
                    changed = true;
                } else if (result.endsWith(keyword)) {
                    result = result.substring(0, result.length() - keyword.length()).trim();
                    changed = true;
                }
            }
        }
        return result;
    }

    private boolean containsAny(String text, String[] keywords) {
        for (String keyword : keywords) {
            if (text.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    private static final class FastIRFallbackResult {
        final ParseUnit interceptedUnit;
        final String healedText;
        final List<String> matchedItemRealIds;
        final List<String> matchedEntityTypeIds;
        final ReviewHint reviewHint;

        FastIRFallbackResult(
                ParseUnit interceptedUnit,
                String healedText,
                List<String> matchedItemRealIds,
                List<String> matchedEntityTypeIds,
                ReviewHint reviewHint
        ) {
            this.interceptedUnit = interceptedUnit;
            this.healedText = healedText;
            this.matchedItemRealIds = matchedItemRealIds == null ? List.of() : matchedItemRealIds;
            this.matchedEntityTypeIds = matchedEntityTypeIds == null ? List.of() : matchedEntityTypeIds;
            this.reviewHint = reviewHint;
        }
    }

    private static final class ReviewHint {
        static final ReviewHint EMPTY = new ReviewHint("", "", 0.0D, 0.0D, Double.MAX_VALUE, "");

        final String bestCandidateText;
        final String bestCandidateRealItemId;
        final double bestScore;
        final double entityRatio;
        final double interceptThreshold;
        final String candidateIntentType;

        ReviewHint(
                String bestCandidateText,
                String bestCandidateRealItemId,
                double bestScore,
                double entityRatio,
                double interceptThreshold,
                String candidateIntentType
        ) {
            this.bestCandidateText = bestCandidateText;
            this.bestCandidateRealItemId = bestCandidateRealItemId;
            this.bestScore = bestScore;
            this.entityRatio = entityRatio;
            this.interceptThreshold = interceptThreshold;
            this.candidateIntentType = candidateIntentType;
        }
    }
}
