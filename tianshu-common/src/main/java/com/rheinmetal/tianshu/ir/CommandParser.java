package com.rheinmetal.tianshu.ir;

import com.rheinmetal.tianshu.ir.collection.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

public final class CommandParser {

    private static final String[] PRIORITY_SPLITTERS = {"但是", "不过", "然后"};
    private static final String[] PARALLEL_SPLITTERS = {"和", "跟", "与", "、"};
    private static final String[] NEGATIONS = {"不", "别", "不要", "别把", "别动", "别扔"};
    private static final String[] DROP_WORDS = {"扔", "丢", "丢掉", "扔掉", "丢弃", "丢了"};
    private static final String[] STORE_WORDS = {"收", "收起", "存", "存入", "放回", "放进去", "收进去"};
    private static final String[] USE_WORDS = {"用", "使用", "拿", "装备"};
    private static final String[] CRAFT_WORDS = {"做", "合成", "制作"};
    private static final String[] FILLER_WORDS = {"那个", "这个", "一下", "一下子", "给我", "把", "将", "都", "再", "去", "帮我", "帮", "请", "吧"};

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

    private ParseUnit parseSingleSubQuery(SubQuery subQuery, Set<Integer> contextInternalIds) {
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

        ScoredCandidate[] ranked = rankCandidates(topCandidates, variant, contextInternalIds);
        if (ranked.length == 0) {
            return null;
        }

        Arrays.sort(ranked, Comparator.comparingDouble((ScoredCandidate c) -> c.score).reversed());
        ScoredCandidate top1 = ranked[0];
        ScoredCandidate top2 = ranked.length > 1 ? ranked[1] : null;

        if (top1.score < 0.25d) {
            return null;
        }
        if (top2 != null && top1.score - top2.score < 0.1d) {
            return null;
        }

        String targetRealItemId = IRBaseUtils.reverseLookupArray[top1.internalId];
        return new ParseUnit(subQuery.intent, targetRealItemId, subQuery.negFlag);
    }

    private List<SubQuery> splitToSubQueries(String rawText) {
        List<String> primaryParts = splitByKeywords(rawText, PRIORITY_SPLITTERS);
        List<SubQuery> subQueries = new ArrayList<>();
        for (String part : primaryParts) {
            String normalizedPart = normalizeChunk(part);
            if (normalizedPart.isEmpty()) {
                continue;
            }
            boolean parentNeg = containsAny(normalizedPart, NEGATIONS);
            Intent parentIntent = detectIntent(normalizedPart);
            List<String> secondaryParts = splitByKeywords(normalizedPart, PARALLEL_SPLITTERS);
            for (String subPart : secondaryParts) {
                String normalized = normalizeChunk(subPart);
                if (normalized.isEmpty()) {
                    continue;
                }
                boolean localNeg = containsAny(normalized, NEGATIONS);
                boolean neg = localNeg || parentNeg;
                Intent localIntent = detectIntent(normalized);
                Intent intent = localIntent == Intent.UNKNOWN ? parentIntent : localIntent;
                String entityChunk = extractEntityChunk(normalized);
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
        for (String filler : FILLER_WORDS) {
            result = result.replace(filler, " ");
        }
        return result.replace('，', ' ').replace(',', ' ').trim();
    }

    private Intent detectIntent(String text) {
        if (containsAny(text, DROP_WORDS)) {
            return Intent.DROP;
        }
        if (containsAny(text, STORE_WORDS)) {
            return Intent.STORE;
        }
        if (containsAny(text, CRAFT_WORDS)) {
            return Intent.CRAFT;
        }
        if (containsAny(text, USE_WORDS)) {
            return Intent.USE;
        }
        return Intent.UNKNOWN;
    }

    private String extractEntityChunk(String text) {
        String result = text;
        result = stripKeywords(result, NEGATIONS);
        result = stripKeywords(result, DROP_WORDS);
        result = stripKeywords(result, STORE_WORDS);
        result = stripKeywords(result, USE_WORDS);
        result = stripKeywords(result, CRAFT_WORDS);
        result = stripKeywords(result, FILLER_WORDS);
        return result.trim();
    }

    private String stripKeywords(String text, String[] keywords) {
        String result = text;
        for (String keyword : keywords) {
            result = result.replace(keyword, " ");
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
            int entityLength = IndexBuilder.entityPinyinLengthArray[candidateId];
            if (Math.abs(entityLength - queryLength) > 2) {
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

    private ScoredCandidate[] rankCandidates(Candidate[] candidates, QueryVariant variant, Set<Integer> contextInternalIds) {
        List<ScoredCandidate> ranked = new ArrayList<>(candidates.length);
        LcsWorkspace lcsWorkspace = new LcsWorkspace();
        for (Candidate candidate : candidates) {
            String[] candidateTokens = IndexBuilder.entityTokenArray[candidate.internalId];
            if (candidateTokens.length == 0) {
                continue;
            }
            String candidateJoined = IndexBuilder.entityJoinedTokenArray[candidate.internalId];
            double lcsRatio = computeLcsRatio(variant.joined, candidateJoined, lcsWorkspace);
            double overlapRatio = computeCharOverlapRatio(variant.joined, candidateJoined);
            double score = (lcsRatio * 0.6d) + (overlapRatio * 0.4d);
            if (candidateTokens.length < 3) {
                score *= 0.5d;
            }
            if (variant.baseTokens.length <= 3 && contextInternalIds.contains(candidate.internalId)) {
                score += 10.0d;
            } else if (variant.baseTokens.length > 3 && contextInternalIds.contains(candidate.internalId)) {
                score += 0.3d;
            }
            ranked.add(new ScoredCandidate(candidate.internalId, score));
        }
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
