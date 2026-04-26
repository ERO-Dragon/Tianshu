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

    // ========== 物理外挂日志方法 ==========
    private static void debugLog(String msg) {
        try {
            // 直接写到游戏运行目录的 logs 文件夹下
            Path logPath = Paths.get("logs", "ir_debug.txt");
            String line = "[" + System.currentTimeMillis() + "] " + msg + "\n";
            Files.write(logPath, line.getBytes(), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            e.printStackTrace(); // 如果连文件都写不了，那就真的只能抛异常了
        }
    }
    // =====================================

    public CommandParser() {
        Map<String, String[]> keywords = IntentKeywordLoader.load();
        this.prioritySplitters = keywords.getOrDefault("PRIORITY_SPLITTERS", new String[0]);
        this.parallelSplitters = keywords.getOrDefault("PARALLEL_SPLITTERS", new String[0]);
        this.negations = keywords.getOrDefault("NEGATIONS", new String[0]);
        this.fillerWords = keywords.getOrDefault("FILLER_WORDS", new String[0]);
        this.detectableIntents = IntentKeywordLoader.getDetectableIntents().toArray(new Intent[0]);
    }

    public List<ParseUnit> parse(String rawText, Set<Integer> contextInternalIds) {
        if (rawText == null || rawText.isBlank()) {
            return List.of();
        }

        List<SubQuery> subQueries = splitToSubQueries(rawText);
        if (subQueries.isEmpty()) {
            return List.of();
        }

        List<ParseUnit> results = new ArrayList<>(subQueries.size());
        for (SubQuery subQuery : subQueries) {
            ParseUnit unit = parseSingleSubQuery(subQuery, contextInternalIds);
            if (unit != null) {
                results.add(unit);
            }
        }
        return results;
    }

    private ParseUnit parseSingleSubQuery(SubQuery subQuery, Set<Integer> contextInternalIds) {// ===== 【日志 1：看意图剥离后，剩下的实体到底是什么】 =====

        debugLog("[DEBUG-1 实体提取] rawChunk = " + subQuery.rawChunk);

        String[] tokens = IRBaseUtils.tokenize(subQuery.rawChunk);
        
        // ===== 【日志 2：看分词和转拼音到底对不对】 =====
        debugLog("[DEBUG-2 Tokenize] 结果 = " + Arrays.toString(tokens) + " | 长度 = " + tokens.length);

        if (tokens.length < 2) {
            debugLog("[DEBUG-2] 长度不足2，已被拦截！");
            return null;
        }
        debugLog("[DEBUG] 提取实体: " + subQuery.rawChunk + " -> 分词结果: " + Arrays.toString(tokens));
        if (tokens.length < 2) {
            return null;
        }

        QueryVariant variant = buildQueryVariant(tokens);
        Int2ObjectOpenHashMap<MutableVote> votes = new Int2ObjectOpenHashMap<>(64);
        int queryTotalGramCount = collectVotes(variant, votes);
        // ===== 【日志 3：看是不是被 Stop-Gram 黑名单杀光了】 =====
        debugLog("[DEBUG-3 投票阶段] queryTotalGramCount = " + queryTotalGramCount + " | votesSize = " + votes.size());

        if (queryTotalGramCount == 0) {
            debugLog("[DEBUG-3] 有效 gram 为 0，已被拦截！");
            return null;
        }

        Candidate[] topCandidates = collectTopCandidates(votes, queryTotalGramCount, variant.joined.length());
        if (topCandidates.length == 0) {
            return null;
        }
        debugLog("[DEBUG-4 排序阶段] topCandidates 剩余数量 = " + topCandidates.length); // 必须去掉注释！

        ScoredCandidate[] ranked = rankCandidates(topCandidates, variant, contextInternalIds);
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
            int candidateId = voteIterator.key(); int voteCount = voteIterator.value().count;
            if (queryTotalGramCount >= 3 && voteCount < 2) { continue; }
            candidates.add(new Candidate(candidateId, voteCount));
        }
        if (candidates.isEmpty()) { return new Candidate[0]; }
        candidates.sort(Comparator.comparingInt((Candidate c) -> c.voteCount).reversed());
        int limit = Math.min(50, candidates.size());
        return candidates.subList(0, limit).toArray(new Candidate[0]);
      }

    private ScoredCandidate[] rankCandidates(Candidate[] candidates, QueryVariant variant, Set<Integer> contextInternalIds) {
        List<ScoredCandidate> ranked = new ArrayList<>(candidates.length);
        LcsWorkspace lcsWorkspace = new LcsWorkspace();
        final double PRIMARY_WEIGHT = 1.0;   // 中文绝对优先
        final double FALLBACK_WEIGHT = 0.85; // 英文兜底，防无中文Mod死档

debugLog("[RANK-基准] 用户输入拼接: " + variant.joined + " (长度:" + variant.joined.length() + ")");

        for (Candidate candidate : candidates) {
            int internalId = candidate.internalId;
            String[] pTokens = IRBaseUtils.primaryAliasTokensArray[internalId];
            String[] fTokens = IRBaseUtils.fallbackAliasTokensArray[internalId];

            if ((pTokens == null || pTokens.length == 0) && (fTokens == null || fTokens.length == 0)) { continue; }

            String realItemId = IRBaseUtils.reverseLookupArray[internalId];

            // --- 1. 计算主轨道分数 (中文) ---
            double pFinalScore = 0.0;
            if (pTokens != null && pTokens.length > 0) {
                String pJoined = IRBaseUtils.joinTokens(pTokens);
debugLog("[RANK-主轨道] 候选: " + realItemId + " | 索引串: " + pJoined);
                double pLcs = computeLcsRatio(variant.joined, pJoined, lcsWorkspace);
                double pOverlap = computeCharOverlapRatio(variant.joined, pJoined);
                double pBaseScore = (pLcs * 0.6d) + (pOverlap * 0.4d);
                int pLenDiff = Math.abs(pJoined.length() - variant.joined.length());
                // 修正了原来的 Bug: 原来是 > 2 里面套了 - 6
                double pPenalty = (pLenDiff > 6) ? (pLenDiff - 6) * 0.03d : 0.0;
                pFinalScore = (pBaseScore * PRIMARY_WEIGHT) - pPenalty;
debugLog("[RANK-主轨道得分] 基础=" + String.format("%.4f", pBaseScore) + ", 扣分=" + String.format("%.4f", pPenalty) + ", 加权后=" + String.format("%.4f", pFinalScore));
            }

            // --- 2. 计算副轨道分数 (英文兜底) ---
            double fFinalScore = 0.0;
            if (fTokens != null && fTokens.length > 0) {
                String fJoined = IRBaseUtils.joinTokens(fTokens);
debugLog("[RANK-副轨道] 候选: " + realItemId + " | 索引串: " + fJoined);
                double fLcs = computeLcsRatio(variant.joined, fJoined, lcsWorkspace);
                double fOverlap = computeCharOverlapRatio(variant.joined, fJoined);
                double fBaseScore = (fLcs * 0.6d) + (fOverlap * 0.4d);
                int fLenDiff = Math.abs(fJoined.length() - variant.joined.length());
                double fPenalty = (fLenDiff > 6) ? (fLenDiff - 6) * 0.03d : 0.0;
                fFinalScore = (fBaseScore * FALLBACK_WEIGHT) - fPenalty;
debugLog("[RANK-副轨道得分] 基础=" + String.format("%.4f", fBaseScore) + ", 扣分=" + String.format("%.4f", fPenalty) + ", 加权后=" + String.format("%.4f", fFinalScore));
            }

            double finalScore = Math.max(pFinalScore, fFinalScore);

            if (variant.baseTokens.length <= 3 && contextInternalIds.contains(internalId)) { finalScore += 10.0d; }
            else if (variant.baseTokens.length > 3 && contextInternalIds.contains(internalId)) { finalScore += 0.3d; }

            ranked.add(new ScoredCandidate(internalId, finalScore));
        }
debugLog("[RANK-结束] 总共参与排序的物品数: " + ranked.size());
        return ranked.toArray(new ScoredCandidate[0]);
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
