package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.context.AXMemoryWindowPolicy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class ShortTermCompressionPlanner {
    private final AXMemoryWindowPolicy policy;

    public ShortTermCompressionPlanner(AXMemoryWindowPolicy policy) {
        this.policy = policy == null ? AXMemoryWindowPolicy.DEFAULT : policy;
    }

    public Optional<ShortTermCompressionCandidate> plan(List<ConversationTurn> rawTurns) {
        if (rawTurns == null || rawTurns.isEmpty()) {
            return Optional.empty();
        }
        List<ConversationTurn> compressible = compressibleTurns(rawTurns);
        if (compressible.isEmpty()) {
            return Optional.empty();
        }
        Optional<ShortTermCompressionCandidate> pauseCandidate = byPauseBoundary(compressible);
        if (pauseCandidate.isPresent()) {
            return pauseCandidate;
        }
        return byTokenWindow(compressible);
    }

    private List<ConversationTurn> compressibleTurns(List<ConversationTurn> rawTurns) {
        int tokens = 0;
        int keepFrom = rawTurns.size();
        for (int i = rawTurns.size() - 1; i >= 0; i--) {
            ConversationTurn turn = rawTurns.get(i);
            if (turn == null || turn.isEmpty()) {
                continue;
            }
            if (!reachedKeepMinimum(tokens) && tokens + turn.estimatedTokens() <= policy.recentRawKeepTokenMax()) {
                tokens += turn.estimatedTokens();
                keepFrom = i;
                continue;
            }
            if (tokens < policy.recentRawKeepTokenTarget()) {
                tokens += turn.estimatedTokens();
                keepFrom = i;
                continue;
            }
            break;
        }
        return rawTurns.subList(0, Math.max(0, keepFrom)).stream()
                .filter(turn -> turn != null && !turn.isEmpty())
                .toList();
    }

    private boolean reachedKeepMinimum(int tokens) {
        return tokens >= policy.recentRawKeepTokenTarget();
    }

    private Optional<ShortTermCompressionCandidate> byPauseBoundary(List<ConversationTurn> turns) {
        if (policy.conversationPauseMillis() <= 0L || turns.size() < 2) {
            return Optional.empty();
        }
        int boundaryExclusive = -1;
        long previousUserAt = 0L;
        for (int i = 0; i < turns.size(); i++) {
            ConversationTurn turn = turns.get(i);
            if (!"user".equals(turn.role())) {
                continue;
            }
            if (previousUserAt > 0L && turn.createdAt() - previousUserAt >= policy.conversationPauseMillis()) {
                boundaryExclusive = i;
            }
            previousUserAt = turn.createdAt();
        }
        if (boundaryExclusive <= 0) {
            return Optional.empty();
        }
        List<ConversationTurn> selected = limitToMax(turns.subList(0, boundaryExclusive));
        if (selected.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ShortTermCompressionCandidate(selected, true, false, tokenCount(selected)));
    }

    private Optional<ShortTermCompressionCandidate> byTokenWindow(List<ConversationTurn> turns) {
        int tokens = 0;
        List<ConversationTurn> selected = new ArrayList<>();
        boolean forced = false;
        for (ConversationTurn turn : turns) {
            if (turn == null || turn.isEmpty()) {
                continue;
            }
            if (!selected.isEmpty() && tokens + turn.estimatedTokens() > policy.shortTermCompressTokenMax()) {
                forced = true;
                break;
            }
            selected.add(turn);
            tokens += turn.estimatedTokens();
            if (tokens >= policy.shortTermCompressTokenTarget()) {
                break;
            }
        }
        if (tokens < policy.shortTermCompressTokenTarget() && tokens < policy.shortTermCompressTokenMax()) {
            return Optional.empty();
        }
        return selected.isEmpty() ? Optional.empty() : Optional.of(new ShortTermCompressionCandidate(selected, false, forced || tokens >= policy.shortTermCompressTokenMax(), tokens));
    }

    private List<ConversationTurn> limitToMax(List<ConversationTurn> turns) {
        List<ConversationTurn> selected = new ArrayList<>();
        int tokens = 0;
        for (ConversationTurn turn : turns) {
            if (turn == null || turn.isEmpty()) {
                continue;
            }
            if (!selected.isEmpty() && tokens + turn.estimatedTokens() > policy.shortTermCompressTokenMax()) {
                break;
            }
            selected.add(turn);
            tokens += turn.estimatedTokens();
        }
        return selected;
    }

    private int tokenCount(List<ConversationTurn> turns) {
        return turns == null ? 0 : turns.stream().mapToInt(ConversationTurn::estimatedTokens).sum();
    }
}
