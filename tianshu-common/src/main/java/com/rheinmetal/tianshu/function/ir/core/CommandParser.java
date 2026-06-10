package com.rheinmetal.tianshu.function.ir.core;

import com.rheinmetal.tianshu.function.ir.core.collection.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommandParser {
    private final String[] prioritySplitters;
    private final String[] parallelSplitters;
    private final String[] negations;
    private final String[] fillerWords;
    private final String[] entityBoundaryWords;

    public static double PRIMARY_WEIGHT = 1.0;
    public static double FALLBACK_WEIGHT = 0.85;

    private static final double FASTIR_HEAL_THRESHOLD = 0.25d;
    private static final double FASTIR_INTERCEPT_HIGH_RATIO = 0.5d;
    private static final double FASTIR_INTERCEPT_HIGH_THRESHOLD = 0.40d;
    private static final double FASTIR_INTERCEPT_MID_RATIO_LOW = 0.2d;
    private static final double FASTIR_INTERCEPT_MID_THRESHOLD = 0.25d;
    private static final double FINALIR_FIXED_THRESHOLD = 0.50d;
    private static final double HEAL_PINYIN_OVERLAP_THRESHOLD = 0.4d;
    private static final int HEAL_MAX_CANDIDATES = 5;

    private static void debugLog(String msg) {
        try {
            Path logPath = Paths.get("logs", "ir_debug.txt");
            String line = "[" + System.currentTimeMillis() + "] " + msg + "\n";
            Files.write(logPath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public CommandParser() {
        Map<String, String[]> keywords = IntentKeywordLoader.load();
        this.prioritySplitters = keywords.getOrDefault("PRIORITY_SPLITTERS", new String[0]);
        this.parallelSplitters = keywords.getOrDefault("PARALLEL_SPLITTERS", new String[0]);
        this.negations = keywords.getOrDefault("NEGATIONS", new String[0]);
        this.fillerWords = keywords.getOrDefault("FILLER_WORDS", new String[0]);
        this.entityBoundaryWords = IntentKeywordLoader.getEntityBoundaryWords();
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
        LcsWorkspace sharedLcsWorkspace = new LcsWorkspace();

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
                ScoredCandidate best = findBestCandidateForIntercept(rawText, contextInternalIds, sharedLcsWorkspace);
                if (best != null && isItemCandidate(best)) {
                    double threshold = FINALIR_FIXED_THRESHOLD;
                    if (best.score >= threshold) {
                        String targetRealItemId = IRObjectId.raw(IRBaseUtils.reverseLookupArray[best.internalId]);
                        results.add(new ParseUnit(Intent.UNKNOWN, targetRealItemId, false));
                    }
                }
            }
        } else if (isFastIR) {
            RepairResult repairResult = repairRawTextFromRanked(rawText, executeRankQuery(rawText, contextInternalIds, sharedLcsWorkspace));
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

        return new IRParseResult(true, rawText, healedRawText, results, matchedItemRealIds, matchedEntityTypeIds, reviewHint.bestCandidateText, IRObjectId.raw(reviewHint.bestCandidateRealItemId), reviewHint.bestScore, reviewHint.entityRatio, reviewHint.interceptThreshold, reviewHint.candidateIntentType);
    }

    private static final class FastIRFallbackResult {
        final ParseUnit interceptedUnit;
        final String healedText;
        final List<String> matchedItemRealIds;
        final List<String> matchedEntityTypeIds;
        final ReviewHint reviewHint;

        FastIRFallbackResult(ParseUnit interceptedUnit, String healedText, List<String> matchedItemRealIds, List<String> matchedEntityTypeIds, ReviewHint reviewHint) {
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

        ReviewHint(String bestCandidateText, String bestCandidateRealItemId, double bestScore, double entityRatio, double interceptThreshold, String candidateIntentType) {
            this.bestCandidateText = bestCandidateText;
            this.bestCandidateRealItemId = bestCandidateRealItemId;
            this.bestScore = bestScore;
            this.entityRatio = entityRatio;
            this.interceptThreshold = interceptThreshold;
            this.candidateIntentType = candidateIntentType;
        }
    }

    private FastIRFallbackResult processFastIRFallback(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);

        ParseUnit interceptedUnit = null;
        ReviewHint reviewHint = ReviewHint.EMPTY;
        if (ranked.length > 0) {
            ScoredCandidate best = ranked[0];
            String targetText = IRBaseUtils.localizedNameArray[best.internalId];
            String targetObjectId = IRBaseUtils.reverseLookupArray[best.internalId];
            double threshold = computeInterceptThreshold(rawText, best);
            double entityRatio = computeEntityRatio(rawText, best);
            reviewHint = new ReviewHint(targetText, targetObjectId, best.score, entityRatio, threshold, Intent.UNKNOWN.name());
            if (isItemCandidate(best) && best.score >= threshold && !isGenericContainedQuery(IRBaseUtils.tokenize(rawText), ranked)) {
                interceptedUnit = new ParseUnit(Intent.UNKNOWN, IRObjectId.raw(targetObjectId), false);
            }
        }

        RepairResult repairResult = repairRawTextFromRanked(rawText, ranked);

        return new FastIRFallbackResult(interceptedUnit, repairResult.text, repairResult.matchedItemRealIds, repairResult.matchedEntityTypeIds, reviewHint);
    }

    private ScoredCandidate[] executeRankQuery(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        String[] tokens = IRBaseUtils.tokenize(rawText);
        if (tokens.length < 2) {
            return new ScoredCandidate[0];
        }

        QueryVariant variant = buildQueryVariant(tokens);
        Int2ObjectOpenHashMap<MutableVote> votes = new Int2ObjectOpenHashMap<>(64);
        int queryTotalGramCount = collectVotes(variant, votes);
        if (queryTotalGramCount == 0) {
            return new ScoredCandidate[0];
        }

        Candidate[] topCandidates = collectTopCandidates(votes, queryTotalGramCount, variant.joined.length());
        if (topCandidates.length == 0) {
            return new ScoredCandidate[0];
        }

        ScoredCandidate[] ranked = rankCandidates(topCandidates, variant, contextInternalIds, lcsWorkspace);
        if (ranked.length == 0) {
            return new ScoredCandidate[0];
        }

        Arrays.sort(ranked, Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed());
        return ranked;
    }

    private double computeInterceptThreshold(String rawText, ScoredCandidate best) {
        String target = IRBaseUtils.localizedNameArray[best.internalId];
        if (target == null || target.isEmpty()) {
            return Double.MAX_VALUE;
        }
        double entityRatio = computeEntityRatio(rawText, best);
        if (entityRatio >= FASTIR_INTERCEPT_HIGH_RATIO) {
            return FASTIR_INTERCEPT_HIGH_THRESHOLD;
        } else if (entityRatio > FASTIR_INTERCEPT_MID_RATIO_LOW) {
            return FASTIR_INTERCEPT_MID_THRESHOLD;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private double computeEntityRatio(String rawText, ScoredCandidate best) {
        String target = IRBaseUtils.localizedNameArray[best.internalId];
        if (target == null || target.isEmpty() || rawText == null || rawText.isEmpty()) {
            return 0.0D;
        }
        return (double) target.length() / rawText.length();
    }

    private ScoredCandidate findBestCandidateForIntercept(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);
        return ranked.length > 0 ? ranked[0] : null;
    }

    private RepairResult repairRawTextFromRanked(String rawText, ScoredCandidate[] ranked) {
        if (ranked.length == 0) {
            return new RepairResult(rawText, List.of());
        }

        List<RepairProposal> proposals = collectRepairProposals(rawText, ranked);
        if (proposals.isEmpty()) {
            return new RepairResult(rawText, List.of());
        }

        proposals.sort(Comparator
                .comparingDouble((RepairProposal p) -> p.overlap).reversed()
                .thenComparing(Comparator.comparingInt((RepairProposal p) -> p.target.length()).reversed())
                .thenComparing(Comparator.comparingDouble((RepairProposal p) -> p.candidateScore).reversed())
                .thenComparingInt(p -> p.start));

        boolean[] occupied = new boolean[rawText.length()];
        List<RepairProposal> selected = new ArrayList<>();
        for (RepairProposal proposal : proposals) {
            if (overlapsOccupied(occupied, proposal.start, proposal.end)) {
                continue;
            }
            markOccupied(occupied, proposal.start, proposal.end);
            selected.add(proposal);
        }
        if (selected.isEmpty()) {
            return new RepairResult(rawText, List.of());
        }
        selected.sort(Comparator.comparingInt(p -> p.start));

        StringBuilder builder = new StringBuilder(rawText.length());
        LinkedHashSet<String> matchedIds = new LinkedHashSet<>();
        int cursor = 0;
        for (RepairProposal proposal : selected) {
            if (proposal.start > cursor) {
                builder.append(rawText, cursor, proposal.start);
            }
            builder.append(proposal.target);
            matchedIds.add(IRBaseUtils.reverseLookupArray[proposal.internalId]);
            cursor = proposal.end;
        }
        if (cursor < rawText.length()) {
            builder.append(rawText, cursor, rawText.length());
        }
        return RepairResult.from(builder.toString(), matchedIds);
    }

    private List<RepairProposal> collectRepairProposals(String rawText, ScoredCandidate[] ranked) {
        List<RepairProposal> proposals = new ArrayList<>();
        int limit = Math.min(HEAL_MAX_CANDIDATES, ranked.length);
        for (int ci = 0; ci < limit; ci++) {
            ScoredCandidate sc = ranked[ci];
            String target = IRBaseUtils.localizedNameArray[sc.internalId];
            if (target == null || target.isEmpty()) {
                continue;
            }

            RepairProposal proposal = findBestRepairProposal(rawText, sc.internalId, target, sc.score);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        return proposals;
    }

    private RepairProposal findBestRepairProposal(String rawText, int internalId, String target, double candidateScore) {
        int minLen = Math.max(2, target.length() - 1);
        int maxLen = Math.min(rawText.length(), target.length() + 1);

        String[] targetPinyinTokens = IRBaseUtils.tokenize(target);
        String targetPinyinJoined = IRBaseUtils.joinTokens(targetPinyinTokens);
        LcsWorkspace windowLcsWorkspace = new LcsWorkspace();

        int bestStart = -1;
        int bestEnd = -1;
        double bestOverlap = 0.0d;
        int bestLengthDiff = Integer.MAX_VALUE;

        for (int windowLen = minLen; windowLen <= maxLen; windowLen++) {
            for (int start = 0; start <= rawText.length() - windowLen; start++) {
                int end = start + windowLen;
                String slice = rawText.substring(start, end);
                String[] slicePinyinTokens = IRBaseUtils.tokenize(slice);
                if (slicePinyinTokens.length == 0) {
                    continue;
                }
                String slicePinyinJoined = IRBaseUtils.joinTokens(slicePinyinTokens);
                double overlap = computeWindowSimilarity(slicePinyinJoined, targetPinyinJoined, windowLcsWorkspace);
                int lengthDiff = Math.abs(windowLen - target.length());
                if (overlap > bestOverlap
                        || (Double.compare(overlap, bestOverlap) == 0 && lengthDiff < bestLengthDiff)
                        || (Double.compare(overlap, bestOverlap) == 0 && lengthDiff == bestLengthDiff && (bestStart < 0 || start < bestStart))) {
                    bestOverlap = overlap;
                    bestStart = start;
                    bestEnd = end;
                    bestLengthDiff = lengthDiff;
                }
            }
        }

        if (bestOverlap > HEAL_PINYIN_OVERLAP_THRESHOLD && bestStart >= 0) {
            return new RepairProposal(internalId, bestStart, bestEnd, target, bestOverlap, candidateScore);
        }
        return null;
    }

    private double computeWindowSimilarity(String slicePinyinJoined, String targetPinyinJoined, LcsWorkspace lcsWorkspace) {
        double lcsRatio = computeLcsRatio(slicePinyinJoined, targetPinyinJoined, lcsWorkspace);
        double overlapRatio = computeCharOverlapRatio(slicePinyinJoined, targetPinyinJoined);
        return (lcsRatio * 0.7d) + (overlapRatio * 0.3d);
    }

    private boolean overlapsOccupied(boolean[] occupied, int start, int end) {
        for (int i = start; i < end && i < occupied.length; i++) {
            if (occupied[i]) {
                return true;
            }
        }
        return false;
    }

    private void markOccupied(boolean[] occupied, int start, int end) {
        for (int i = start; i < end && i < occupied.length; i++) {
            occupied[i] = true;
        }
    }

    private ParseUnit parseSingleSubQuery(SubQuery subQuery, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        String[] tokens = IRBaseUtils.tokenize(subQuery.rawChunk);

        if (tokens.length < 2) {
            return null;
        }

        QueryVariant variant = buildQueryVariant(tokens);
        Int2ObjectOpenHashMap<MutableVote> votes = new Int2ObjectOpenHashMap<>(64);
        int queryTotalGramCount = collectVotes(variant, votes);

        if (queryTotalGramCount == 0) {
            return null;
        }

        Candidate[] topCandidates = collectTopCandidates(votes, queryTotalGramCount, variant.joined.length());
        if (topCandidates.length == 0) {
            return null;
        }

        ScoredCandidate[] ranked = rankCandidates(topCandidates, variant, contextInternalIds, lcsWorkspace);
        if (ranked.length == 0) {
            return null;
        }

        Arrays.sort(ranked, Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed());
        ScoredCandidate top1 = ranked[0];
        ScoredCandidate top2 = ranked.length > 1 ? ranked[1] : null;

        if (top1.score < 0.15d) {
            return null;
        }
        if (top2 != null && top1.score - top2.score < 0.1d) {
            return null;
        }
        if (isGenericContainedQuery(variant.baseTokens, ranked)) {
            return null;
        }

        ScoredCandidate selected = selectCandidateByRepairEvidence(subQuery.rawChunk, ranked, top1);
        if (!isItemCandidate(selected)) {
            return null;
        }
        String targetRealItemId = IRObjectId.raw(IRBaseUtils.reverseLookupArray[selected.internalId]);
        return new ParseUnit(subQuery.intent, targetRealItemId, subQuery.negFlag);
    }

    private boolean isItemCandidate(ScoredCandidate candidate) {
        return candidate != null && IRObjectId.isItem(IRBaseUtils.reverseLookupArray[candidate.internalId]);
    }

    private ScoredCandidate selectCandidateByRepairEvidence(String rawText, ScoredCandidate[] ranked, ScoredCandidate fallback) {
        List<RepairProposal> proposals = collectRepairProposals(rawText, ranked);
        if (proposals.isEmpty()) {
            return fallback;
        }
        proposals.sort(Comparator
                .comparingDouble((RepairProposal p) -> p.overlap).reversed()
                .thenComparing(Comparator.comparingInt((RepairProposal p) -> p.target.length()).reversed())
                .thenComparing(Comparator.comparingDouble((RepairProposal p) -> p.candidateScore).reversed())
                .thenComparingInt(p -> p.start));
        int selectedInternalId = proposals.get(0).internalId;
        for (ScoredCandidate candidate : ranked) {
            if (candidate.internalId == selectedInternalId) {
                return candidate;
            }
        }
        return fallback;
    }

    private boolean isGenericContainedQuery(String[] queryTokens, ScoredCandidate[] ranked) {
        if (queryTokens.length == 0) {
            return false;
        }
        String queryJoined = IRBaseUtils.joinTokens(queryTokens);
        int containedLongerCandidates = 0;
        for (ScoredCandidate candidate : ranked) {
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
            if (candidateTokens.length > queryTokens.length) {
                containedLongerCandidates++;
            }
            if (containedLongerCandidates >= 2) {
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
            List<String> secondaryParts = splitByKeywords(normalizedPart, parallelSplitters);
            for (String subPart : secondaryParts) {
                String normalized = normalizeChunk(subPart);
                if (normalized.isEmpty()) {
                    continue;
                }
                boolean localNeg = containsAny(normalized, negations);
                boolean neg = localNeg || parentNeg;
                String entityChunk = extractEntityChunk(normalized);
                if (!entityChunk.isEmpty()) {
                    subQueries.add(new SubQuery(entityChunk, neg, Intent.UNKNOWN));
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
        String result = text;
        result = stripKeywords(result, negations);
        result = stripBoundaryKeywords(result, entityBoundaryWords);
        result = stripKeywords(result, fillerWords);
        return result.trim();
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
                if (keyword.isEmpty()) continue;
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

    private QueryVariant buildQueryVariant(String[] baseTokens) {
        String[] normalized = baseTokens;
        String[] accentVariant = Arrays.copyOf(normalized, normalized.length);
        for (int i = 0; i < accentVariant.length; i++) {
            accentVariant[i] = applyAccentFallback(accentVariant[i]);
        }
        return new QueryVariant(normalized, accentVariant, IRBaseUtils.joinTokens(normalized));
    }

    private String applyAccentFallback(String token) {
        String result = token;
        if (result.contains("zh")) result = result.replace("zh", "z");
        if (result.contains("ch")) result = result.replace("ch", "c");
        if (result.contains("sh")) result = result.replace("sh", "s");
        if (result.contains("eng")) result = result.replace("eng", "en");
        if (result.contains("ing")) result = result.replace("ing", "in");
        return result;
    }

    private int collectVotes(QueryVariant variant, Int2ObjectOpenHashMap<MutableVote> votes) {
        int queryTotalGramCount = 0;
        queryTotalGramCount += collectVotesForTokens(variant.baseTokens, votes);
        if (!Arrays.equals(variant.baseTokens, variant.accentTokens)) {
            queryTotalGramCount += collectVotesForTokens(variant.accentTokens, votes);
        }
        return queryTotalGramCount;
    }

    private int collectVotesForTokens(String[] tokens, Int2ObjectOpenHashMap<MutableVote> votes) {
        int tokenCount = tokens.length;
        int gramCount = 0;
        for (int gramSize = 2; gramSize <= 3; gramSize++) {
            if (tokenCount < gramSize) {
                continue;
            }
            for (int i = 0; i <= tokenCount - gramSize; i++) {
                int hash = IRBaseUtils.fnv1a32(IRBaseUtils.buildGram(tokens, i, gramSize));
                gramCount++;
                long packed = IndexBuilder.indexDirectory.get(hash & 0xffffffffL);
                if (packed == 0L) {
                    continue;
                }
                int offset = (int) (packed >>> 32);
                int length = (int) packed;
                for (int p = 0; p < length; p++) {
                    int internalId = IndexBuilder.INDEX_POOL[offset + p];
                    MutableVote vote = votes.computeIfAbsent(internalId, ignored -> new MutableVote());
                    vote.count++;
                }
            }
        }
        return gramCount;
    }

    private Candidate[] collectTopCandidates(Int2ObjectOpenHashMap<MutableVote> votes, int queryTotalGramCount, int queryLength) {
        List<Candidate> candidates = new ArrayList<>(votes.size());
        Int2ObjectOpenHashMap.EntryIterator<MutableVote> voteIterator = votes.entryIterator();
        while (voteIterator.next()) {
            int candidateId = voteIterator.key();
            int voteCount = voteIterator.value().count;
            if (queryTotalGramCount >= 3 && voteCount < 2) {
                continue;
            }
            candidates.add(new Candidate(candidateId, voteCount));
        }
        if (candidates.isEmpty()) {
            return new Candidate[0];
        }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.voteCount).reversed());
        int limit = Math.min(50, candidates.size());
        return candidates.subList(0, limit).toArray(new Candidate[0]);
    }

    private ScoredCandidate[] rankCandidates(Candidate[] candidates, QueryVariant variant, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        List<ScoredCandidate> ranked = new ArrayList<>(candidates.length);

        for (Candidate candidate : candidates) {
            int internalId = candidate.internalId;
            String[] pTokens = IRBaseUtils.primaryAliasTokensArray[internalId];
            String[] fTokens = IRBaseUtils.fallbackAliasTokensArray[internalId];

            if ((pTokens == null || pTokens.length == 0) && (fTokens == null || fTokens.length == 0)) {
                continue;
            }

            String realItemId = IRBaseUtils.reverseLookupArray[internalId];

            double pFinalScore = 0.0d;
            if (pTokens != null && pTokens.length > 0) {
                double pBaseScore = calculateBaseScore(variant.joined, pTokens, lcsWorkspace);
                String pJoined = IRBaseUtils.joinTokens(pTokens);
                int pLenDiff = Math.abs(pJoined.length() - variant.joined.length());
                double pPenalty = (pLenDiff > 6) ? (pLenDiff - 6) * 0.03d : 0.0d;
                pFinalScore = (pBaseScore * PRIMARY_WEIGHT) - pPenalty;
            }

            double fFinalScore = 0.0d;
            if (fTokens != null && fTokens.length > 0) {
                double fBaseScore = calculateBaseScore(variant.joined, fTokens, lcsWorkspace);
                String fJoined = IRBaseUtils.joinTokens(fTokens);
                int fLenDiff = Math.abs(fJoined.length() - variant.joined.length());
                double fPenalty = (fLenDiff > 6) ? (fLenDiff - 6) * 0.03d : 0.0d;
                fFinalScore = (fBaseScore * FALLBACK_WEIGHT) - fPenalty;
            }

            double finalScore = Math.max(pFinalScore, fFinalScore);

            if (variant.baseTokens.length <= 3 && contextInternalIds.contains(internalId)) {
                finalScore += 10.0d;
            } else if (variant.baseTokens.length > 3 && contextInternalIds.contains(internalId)) {
                finalScore += 0.3d;
            }

            ranked.add(new ScoredCandidate(internalId, finalScore));
        }
        return ranked.toArray(new ScoredCandidate[0]);
    }

    private double calculateBaseScore(String queryJoined, String[] candidateTokens, LcsWorkspace lcsWorkspace) {
        String candidateJoined = IRBaseUtils.joinTokens(candidateTokens);
        double lcsRatio = computeLcsRatio(queryJoined, candidateJoined, lcsWorkspace);
        double overlapRatio = computeCharOverlapRatio(queryJoined, candidateJoined);
        return (lcsRatio * 0.6d) + (overlapRatio * 0.4d);
    }

    private double computeLcsRatio(String a, String b, LcsWorkspace workspace) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        workspace.ensureCapacity(b.length() + 1);
        Arrays.fill(workspace.previous, 0, b.length() + 1, 0);
        for (int i = 1; i <= a.length(); i++) {
            char ca = a.charAt(i - 1);
            workspace.current[0] = 0;
            for (int j = 1; j <= b.length(); j++) {
                if (ca == b.charAt(j - 1)) {
                    workspace.current[j] = workspace.previous[j - 1] + 1;
                } else {
                    workspace.current[j] = Math.max(workspace.previous[j], workspace.current[j - 1]);
                }
            }
            workspace.swap();
        }
        int lcs = workspace.previous[b.length()];
        return (double) lcs / Math.max(a.length(), b.length());
    }

    private double computeCharOverlapRatio(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0d;
        }
        int[] freq = new int[256];
        for (int i = 0; i < a.length(); i++) {
            char c = a.charAt(i);
            if (c < 256) {
                freq[c]++;
            }
        }
        int overlap = 0;
        for (int i = 0; i < b.length(); i++) {
            char c = b.charAt(i);
            if (c < 256 && freq[c] > 0) {
                freq[c]--;
                overlap++;
            }
        }
        return (double) overlap / Math.max(a.length(), b.length());
    }

    private static final class QueryVariant {
        final String[] baseTokens;
        final String[] accentTokens;
        final String joined;

        private QueryVariant(String[] baseTokens, String[] accentTokens, String joined) {
            this.baseTokens = baseTokens;
            this.accentTokens = accentTokens;
            this.joined = joined;
        }
    }

    private static final class MutableVote {
        int count;
    }

    private static final class Candidate {
        final int internalId;
        final int voteCount;

        private Candidate(int internalId, int voteCount) {
            this.internalId = internalId;
            this.voteCount = voteCount;
        }
    }

    private static final class ScoredCandidate {
        final int internalId;
        final double score;

        private ScoredCandidate(int internalId, double score) {
            this.internalId = internalId;
            this.score = score;
        }
    }

    private static final class RepairProposal {
        final int internalId;
        final int start;
        final int end;
        final String target;
        final double overlap;
        final double candidateScore;

        private RepairProposal(int internalId, int start, int end, String target, double overlap, double candidateScore) {
            this.internalId = internalId;
            this.start = start;
            this.end = end;
            this.target = target;
            this.overlap = overlap;
            this.candidateScore = candidateScore;
        }
    }

    private static final class RepairResult {
        final String text;
        final List<String> matchedItemRealIds;
        final List<String> matchedEntityTypeIds;

        private RepairResult(String text, List<String> matchedItemRealIds) {
            this(text, matchedItemRealIds, List.of());
        }

        private RepairResult(String text, List<String> matchedItemRealIds, List<String> matchedEntityTypeIds) {
            this.text = text;
            this.matchedItemRealIds = matchedItemRealIds == null ? List.of() : matchedItemRealIds;
            this.matchedEntityTypeIds = matchedEntityTypeIds == null ? List.of() : matchedEntityTypeIds;
        }

        private static RepairResult from(String text, LinkedHashSet<String> objectIds) {
            LinkedHashSet<String> itemIds = new LinkedHashSet<>();
            LinkedHashSet<String> entityTypeIds = new LinkedHashSet<>();
            for (String objectId : objectIds) {
                if (IRObjectId.isEntity(objectId)) {
                    entityTypeIds.add(IRObjectId.raw(objectId));
                } else {
                    itemIds.add(IRObjectId.raw(objectId));
                }
            }
            return new RepairResult(text, List.copyOf(itemIds), List.copyOf(entityTypeIds));
        }
    }

    private static final class LcsWorkspace {
        int[] previous = new int[16];
        int[] current = new int[16];

        void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity <= previous.length) {
                return;
            }
            previous = new int[requiredCapacity];
            current = new int[requiredCapacity];
        }

        void swap() {
            int[] temp = previous;
            previous = current;
            current = temp;
        }
    }
}
