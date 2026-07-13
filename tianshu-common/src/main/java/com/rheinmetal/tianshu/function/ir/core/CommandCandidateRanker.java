package com.rheinmetal.tianshu.function.ir.core;

import com.rheinmetal.tianshu.function.ir.core.collection.Int2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Resolves indexed IR objects into score-ordered candidates for one input fragment.
 *
 * <p>This class owns the retrieval and similarity mechanics only. It does not decide
 * whether a candidate represents a command, nor does it mutate user-facing text.</p>
 */
final class CommandCandidateRanker {
    private static final int MIN_GRAM_SIZE = 2;
    private static final int MAX_GRAM_SIZE = 3;
    private static final int MIN_VOTES_FOR_MULTI_GRAM_QUERY = 2;
    private static final int MAX_CANDIDATES = 50;
    private static final int LENGTH_PENALTY_FREE_DIFFERENCE = 6;
    private static final double LENGTH_PENALTY_PER_CHARACTER = 0.03D;
    private static final int SHORT_QUERY_TOKEN_LIMIT = 3;
    private static final double SHORT_QUERY_CONTEXT_BONUS = 10.0D;
    private static final double LONG_QUERY_CONTEXT_BONUS = 0.3D;

    private final CommandParserPolicy policy;

    CommandCandidateRanker(CommandParserPolicy policy) {
        this.policy = policy;
    }

    ScoredCandidate[] rank(String rawText, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        String[] tokens = IRBaseUtils.tokenize(rawText);
        if (tokens.length < MIN_GRAM_SIZE) {
            return new ScoredCandidate[0];
        }
        return rank(tokens, contextInternalIds, lcsWorkspace);
    }

    ScoredCandidate[] rank(String[] baseTokens, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        if (baseTokens.length < MIN_GRAM_SIZE) {
            return new ScoredCandidate[0];
        }

        QueryVariant variant = buildQueryVariant(baseTokens);
        Int2ObjectOpenHashMap<MutableVote> votes = new Int2ObjectOpenHashMap<>(64);
        int queryTotalGramCount = collectVotes(variant, votes);
        if (queryTotalGramCount == 0) {
            return new ScoredCandidate[0];
        }

        Candidate[] topCandidates = collectTopCandidates(votes, queryTotalGramCount);
        if (topCandidates.length == 0) {
            return new ScoredCandidate[0];
        }

        ScoredCandidate[] ranked = rankCandidates(topCandidates, variant, contextInternalIds, lcsWorkspace);
        Arrays.sort(ranked, Comparator.comparingDouble((ScoredCandidate candidate) -> candidate.score).reversed());
        return ranked;
    }

    private QueryVariant buildQueryVariant(String[] baseTokens) {
        String[] accentVariant = Arrays.copyOf(baseTokens, baseTokens.length);
        for (int index = 0; index < accentVariant.length; index++) {
            accentVariant[index] = applyAccentFallback(accentVariant[index]);
        }
        return new QueryVariant(baseTokens, accentVariant, IRBaseUtils.joinTokens(baseTokens));
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
        int queryTotalGramCount = collectVotesForTokens(variant.baseTokens, votes);
        if (!Arrays.equals(variant.baseTokens, variant.accentTokens)) {
            queryTotalGramCount += collectVotesForTokens(variant.accentTokens, votes);
        }
        return queryTotalGramCount;
    }

    private int collectVotesForTokens(String[] tokens, Int2ObjectOpenHashMap<MutableVote> votes) {
        int gramCount = 0;
        for (int gramSize = MIN_GRAM_SIZE; gramSize <= MAX_GRAM_SIZE; gramSize++) {
            if (tokens.length < gramSize) {
                continue;
            }
            for (int index = 0; index <= tokens.length - gramSize; index++) {
                int hash = IRBaseUtils.fnv1a32(IRBaseUtils.buildGram(tokens, index, gramSize));
                gramCount++;
                long packed = IndexBuilder.indexDirectory.get(hash & 0xffffffffL);
                if (packed == 0L) {
                    continue;
                }
                int offset = (int) (packed >>> 32);
                int length = (int) packed;
                for (int poolIndex = 0; poolIndex < length; poolIndex++) {
                    int internalId = IndexBuilder.INDEX_POOL[offset + poolIndex];
                    votes.computeIfAbsent(internalId, ignored -> new MutableVote()).count++;
                }
            }
        }
        return gramCount;
    }

    private Candidate[] collectTopCandidates(Int2ObjectOpenHashMap<MutableVote> votes, int queryTotalGramCount) {
        List<Candidate> candidates = new ArrayList<>(votes.size());
        Int2ObjectOpenHashMap.EntryIterator<MutableVote> iterator = votes.entryIterator();
        while (iterator.next()) {
            int voteCount = iterator.value().count;
            if (queryTotalGramCount >= MAX_GRAM_SIZE && voteCount < MIN_VOTES_FOR_MULTI_GRAM_QUERY) {
                continue;
            }
            candidates.add(new Candidate(iterator.key(), voteCount));
        }
        candidates.sort(Comparator.comparingInt((Candidate candidate) -> candidate.voteCount).reversed());
        int limit = Math.min(MAX_CANDIDATES, candidates.size());
        return candidates.subList(0, limit).toArray(new Candidate[0]);
    }

    private ScoredCandidate[] rankCandidates(Candidate[] candidates, QueryVariant variant, Set<Integer> contextInternalIds, LcsWorkspace lcsWorkspace) {
        List<ScoredCandidate> ranked = new ArrayList<>(candidates.length);
        for (Candidate candidate : candidates) {
            int internalId = candidate.internalId;
            String[] primaryTokens = IRBaseUtils.primaryAliasTokensArray[internalId];
            String[] fallbackTokens = IRBaseUtils.fallbackAliasTokensArray[internalId];
            if ((primaryTokens == null || primaryTokens.length == 0) && (fallbackTokens == null || fallbackTokens.length == 0)) {
                continue;
            }

            double primaryScore = scoreAlias(variant.joined, primaryTokens, policy.primaryWeight(), lcsWorkspace);
            double fallbackScore = scoreAlias(variant.joined, fallbackTokens, policy.fallbackWeight(), lcsWorkspace);
            double finalScore = Math.max(primaryScore, fallbackScore);
            if (contextInternalIds.contains(internalId)) {
                finalScore += variant.baseTokens.length <= SHORT_QUERY_TOKEN_LIMIT
                        ? SHORT_QUERY_CONTEXT_BONUS
                        : LONG_QUERY_CONTEXT_BONUS;
            }
            ranked.add(new ScoredCandidate(internalId, finalScore));
        }
        return ranked.toArray(new ScoredCandidate[0]);
    }

    private double scoreAlias(String queryJoined, String[] candidateTokens, double weight, LcsWorkspace lcsWorkspace) {
        if (candidateTokens == null || candidateTokens.length == 0) {
            return 0.0D;
        }
        String candidateJoined = IRBaseUtils.joinTokens(candidateTokens);
        int lengthDifference = Math.abs(candidateJoined.length() - queryJoined.length());
        double penalty = lengthDifference > LENGTH_PENALTY_FREE_DIFFERENCE
                ? (lengthDifference - LENGTH_PENALTY_FREE_DIFFERENCE) * LENGTH_PENALTY_PER_CHARACTER
                : 0.0D;
        return (calculateBaseScore(queryJoined, candidateJoined, lcsWorkspace) * weight) - penalty;
    }

    private double calculateBaseScore(String queryJoined, String candidateJoined, LcsWorkspace lcsWorkspace) {
        double lcsRatio = computeLcsRatio(queryJoined, candidateJoined, lcsWorkspace);
        double overlapRatio = computeCharOverlapRatio(queryJoined, candidateJoined);
        return (lcsRatio * 0.6D) + (overlapRatio * 0.4D);
    }

    static double computeLcsRatio(String first, String second, LcsWorkspace workspace) {
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0D;
        }
        workspace.ensureCapacity(second.length() + 1);
        Arrays.fill(workspace.previous, 0, second.length() + 1, 0);
        for (int firstIndex = 1; firstIndex <= first.length(); firstIndex++) {
            char firstCharacter = first.charAt(firstIndex - 1);
            workspace.current[0] = 0;
            for (int secondIndex = 1; secondIndex <= second.length(); secondIndex++) {
                workspace.current[secondIndex] = firstCharacter == second.charAt(secondIndex - 1)
                        ? workspace.previous[secondIndex - 1] + 1
                        : Math.max(workspace.previous[secondIndex], workspace.current[secondIndex - 1]);
            }
            workspace.swap();
        }
        return (double) workspace.previous[second.length()] / Math.max(first.length(), second.length());
    }

    static double computeCharOverlapRatio(String first, String second) {
        if (first.isEmpty() || second.isEmpty()) {
            return 0.0D;
        }
        int[] frequency = new int[256];
        for (int index = 0; index < first.length(); index++) {
            char character = first.charAt(index);
            if (character < 256) {
                frequency[character]++;
            }
        }
        int overlap = 0;
        for (int index = 0; index < second.length(); index++) {
            char character = second.charAt(index);
            if (character < 256 && frequency[character] > 0) {
                frequency[character]--;
                overlap++;
            }
        }
        return (double) overlap / Math.max(first.length(), second.length());
    }

    static final class ScoredCandidate {
        final int internalId;
        final double score;

        ScoredCandidate(int internalId, double score) {
            this.internalId = internalId;
            this.score = score;
        }
    }

    static final class LcsWorkspace {
        int[] previous = new int[16];
        int[] current = new int[16];

        void ensureCapacity(int requiredCapacity) {
            if (requiredCapacity > previous.length) {
                previous = new int[requiredCapacity];
                current = new int[requiredCapacity];
            }
        }

        void swap() {
            int[] temporary = previous;
            previous = current;
            current = temporary;
        }
    }

    private record QueryVariant(String[] baseTokens, String[] accentTokens, String joined) {
    }

    private static final class MutableVote {
        int count;
    }

    private record Candidate(int internalId, int voteCount) {
    }
}
