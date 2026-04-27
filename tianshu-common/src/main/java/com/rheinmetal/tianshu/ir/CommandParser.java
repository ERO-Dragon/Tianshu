package com.rheinmetal.tianshu.ir;

import com.rheinmetal.tianshu.ir.collection.Int2ObjectOpenHashMap;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CommandParser {
    private final String[] prioritySplitters;
    private final String[] parallelSplitters;
    private final String[] negations;
    private final String[] fillerWords;
    private final Intent[] detectableIntents;

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
        this.detectableIntents = IntentKeywordLoader.getDetectableIntents().toArray(new Intent[0]);
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

        if (results.isEmpty()) {
            if (isFastIR) {
                FastIRFallbackResult fallback = processFastIRFallback(rawText, contextInternalIds, sharedLcsWorkspace);
                healedRawText = fallback.healedText;
                if (fallback.interceptedUnit != null) {
                    results.add(fallback.interceptedUnit);
                }
            } else {
                ScoredCandidate best = findBestCandidateForIntercept(rawText, contextInternalIds, sharedLcsWorkspace);
                if (best != null) {
                    double threshold = FINALIR_FIXED_THRESHOLD;
debugLog("[分支A] isFastIR=false, bestScore=" + String.format("%.4f", best.score) + ", threshold=" + String.format("%.4f", threshold));
                    if (best.score >= threshold) {
                        String targetRealItemId = IRBaseUtils.reverseLookupArray[best.internalId];
                        Intent detectedIntent = detectIntent(rawText);
                        results.add(new ParseUnit(detectedIntent, targetRealItemId, false));
                    }
                }
            }
        } else if (isFastIR) {
            healedRawText = healRawTextFromRanked(rawText, executeRankQuery(rawText, contextInternalIds, sharedLcsWorkspace));
        }

        return new IRParseResult(true, rawText, healedRawText, results);
    }

    private static final class FastIRFallbackResult {
        final ParseUnit interceptedUnit;
        final String healedText;

        FastIRFallbackResult(ParseUnit interceptedUnit, String healedText) {
            this.interceptedUnit = interceptedUnit;
            this.healedText = healedText;
        }
    }

    private FastIRFallbackResult processFastIRFallback(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);

        ParseUnit interceptedUnit = null;
        if (ranked.length > 0) {
            ScoredCandidate best = ranked[0];
            double threshold = computeInterceptThreshold(rawText, best);
debugLog("[分支A] isFastIR=true, bestScore=" + String.format("%.4f", best.score) + ", threshold=" + String.format("%.4f", threshold));
            if (best.score >= threshold) {
                String targetRealItemId = IRBaseUtils.reverseLookupArray[best.internalId];
                Intent detectedIntent = detectIntent(rawText);
                interceptedUnit = new ParseUnit(detectedIntent, targetRealItemId, false);
            }
        }

        String healedText = healRawTextFromRanked(rawText, ranked);

        return new FastIRFallbackResult(interceptedUnit, healedText);
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
        double entityRatio = (double) target.length() / rawText.length();
        if (entityRatio >= FASTIR_INTERCEPT_HIGH_RATIO) {
            return FASTIR_INTERCEPT_HIGH_THRESHOLD;
        } else if (entityRatio > FASTIR_INTERCEPT_MID_RATIO_LOW) {
            return FASTIR_INTERCEPT_MID_THRESHOLD;
        } else {
            return Double.MAX_VALUE;
        }
    }

    private ScoredCandidate findBestCandidateForIntercept(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        ScoredCandidate[] ranked = executeRankQuery(rawText, contextInternalIds, lcsWorkspace);
        return ranked.length > 0 ? ranked[0] : null;
    }

    private String healRawTextFromRanked(String rawText, ScoredCandidate[] ranked) {
        if (ranked.length == 0) {
            return rawText;
        }

        StringBuilder builder = new StringBuilder(rawText);
        int offsetAccum = 0;

        int limit = Math.min(HEAL_MAX_CANDIDATES, ranked.length);
        for (int ci = 0; ci < limit; ci++) {
            ScoredCandidate sc = ranked[ci];
            if (sc.score < FASTIR_HEAL_THRESHOLD) {
                break;
            }

            String target = IRBaseUtils.localizedNameArray[sc.internalId];
            if (target == null || target.isEmpty()) {
                continue;
            }

            int minLen = Math.max(2, target.length() - 1);
            int maxLen = Math.min(rawText.length(), target.length() + 1);

            String[] targetPinyinTokens = IRBaseUtils.tokenize(target);
            String targetPinyinJoined = IRBaseUtils.joinTokens(targetPinyinTokens);

            int bestStart = -1;
            int bestEnd = -1;
            double bestOverlap = 0.0d;

            for (int windowLen = minLen; windowLen <= maxLen; windowLen++) {
                for (int start = 0; start <= rawText.length() - windowLen; start++) {
                    int end = start + windowLen;
                    String slice = rawText.substring(start, end);
                    String[] slicePinyinTokens = IRBaseUtils.tokenize(slice);
                    if (slicePinyinTokens.length == 0) {
                        continue;
                    }
                    String slicePinyinJoined = IRBaseUtils.joinTokens(slicePinyinTokens);
                    double overlap = computeCharOverlapRatio(slicePinyinJoined, targetPinyinJoined);
                    if (overlap > bestOverlap) {
                        bestOverlap = overlap;
                        bestStart = start;
                        bestEnd = end;
                    }
                }
            }

            if (bestOverlap > HEAL_PINYIN_OVERLAP_THRESHOLD && bestStart >= 0) {
                int adjustedStart = bestStart + offsetAccum;
                int adjustedEnd = bestEnd + offsetAccum;
                builder.replace(adjustedStart, adjustedEnd, target);
                offsetAccum += target.length() - (bestEnd - bestStart);
debugLog("[治愈] 替换: [" + bestStart + "," + bestEnd + ") -> " + target + ", overlap=" + String.format("%.4f", bestOverlap));
            }
        }

        return builder.toString();
    }

    private ParseUnit parseSingleSubQuery(SubQuery subQuery, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
debugLog("[DEBUG-1 实体提取] rawChunk = " + subQuery.rawChunk);

        String[] tokens = IRBaseUtils.tokenize(subQuery.rawChunk);

debugLog("[DEBUG-2 Tokenize] 结果 = " + Arrays.toString(tokens) + " | 长度 = " + tokens.length);

        if (tokens.length < 2) {
debugLog("[DEBUG-2] 长度不足2，已被拦截！");
            return null;
        }

        QueryVariant variant = buildQueryVariant(tokens);
        Int2ObjectOpenHashMap<MutableVote> votes = new Int2ObjectOpenHashMap<>(64);
        int queryTotalGramCount = collectVotes(variant, votes);
debugLog("[DEBUG-3 投票阶段] queryTotalGramCount = " + queryTotalGramCount + " | votesSize = " + votes.size());

        if (queryTotalGramCount == 0) {
debugLog("[DEBUG-3] 有效 gram 为 0，已被拦截！");
            return null;
        }

        Candidate[] topCandidates = collectTopCandidates(votes, queryTotalGramCount, variant.joined.length());
        if (topCandidates.length == 0) {
            return null;
        }
debugLog("[DEBUG-4 排序阶段] topCandidates 剩余数量 = " + topCandidates.length);

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

        String targetRealItemId = IRBaseUtils.reverseLookupArray[top1.internalId];
        return new ParseUnit(subQuery.intent, targetRealItemId, subQuery.negFlag);
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
            Intent parentIntent = detectIntent(normalizedPart);
            List<String> secondaryParts = splitByKeywords(normalizedPart, parallelSplitters);
            for (String subPart : secondaryParts) {
                String normalized = normalizeChunk(subPart);
                if (normalized.isEmpty()) {
                    continue;
                }
                boolean localNeg = containsAny(normalized, negations);
                boolean neg = localNeg || parentNeg;
                Intent localIntent = detectIntent(normalized);
                Intent intent = localIntent == Intent.UNKNOWN ? parentIntent : localIntent;
                String entityChunk = extractEntityChunk(normalized, intent);
                if (!entityChunk.isEmpty()) {
                    subQueries.add(new SubQuery(entityChunk, neg, intent));
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

    private Intent detectIntent(String text) {
        Intent bestIntent = Intent.UNKNOWN;
        int bestLength = 0;

        for (Intent intent : detectableIntents) {
            for (String keyword : IntentKeywordLoader.getKeywords(intent)) {
                if (text.contains(keyword) && keyword.length() > bestLength) {
                    bestLength = keyword.length();
                    bestIntent = intent;
                }
            }
        }

        return bestIntent;
    }

    private String extractEntityChunk(String text, Intent currentIntent) {
        String result = text;
        result = stripKeywords(result, negations);
        if (currentIntent != Intent.UNKNOWN) {
            result = stripBoundaryKeywords(result, IntentKeywordLoader.getKeywords(currentIntent));
        }
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

debugLog("[RANK-基准] 用户输入拼接: " + variant.joined + " (长度:" + variant.joined.length() + ")");

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
debugLog("[RANK-主轨道] 候选: " + realItemId + " | 基础=" + String.format("%.4f", pBaseScore) + ", 加权后=" + String.format("%.4f", pFinalScore));
            }

            double fFinalScore = 0.0d;
            if (fTokens != null && fTokens.length > 0) {
                double fBaseScore = calculateBaseScore(variant.joined, fTokens, lcsWorkspace);
                String fJoined = IRBaseUtils.joinTokens(fTokens);
                int fLenDiff = Math.abs(fJoined.length() - variant.joined.length());
                double fPenalty = (fLenDiff > 6) ? (fLenDiff - 6) * 0.03d : 0.0d;
                fFinalScore = (fBaseScore * FALLBACK_WEIGHT) - fPenalty;
debugLog("[RANK-副轨道] 候选: " + realItemId + " | 基础=" + String.format("%.4f", fBaseScore) + ", 加权后=" + String.format("%.4f", fFinalScore));
            }

            double finalScore = Math.max(pFinalScore, fFinalScore);

            if (variant.baseTokens.length <= 3 && contextInternalIds.contains(internalId)) {
                finalScore += 10.0d;
            } else if (variant.baseTokens.length > 3 && contextInternalIds.contains(internalId)) {
                finalScore += 0.3d;
            }

            ranked.add(new ScoredCandidate(internalId, finalScore));
        }
debugLog("[RANK-结束] 总共参与排序的物品数: " + ranked.size());
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
