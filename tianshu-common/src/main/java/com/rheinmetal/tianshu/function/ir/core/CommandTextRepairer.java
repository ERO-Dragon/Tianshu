package com.rheinmetal.tianshu.function.ir.core;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;

/** Applies conservative named-object repairs to user text from already-ranked candidates. */
final class CommandTextRepairer {
    private final CommandParserPolicy policy;

    CommandTextRepairer(CommandParserPolicy policy) {
        this.policy = policy;
    }

    RepairResult repair(String rawText, CommandCandidateRanker.ScoredCandidate[] ranked) {
        if (ranked.length == 0) {
            return new RepairResult(rawText, List.of(), List.of());
        }
        List<RepairProposal> proposals = collectRepairProposals(rawText, ranked);
        if (proposals.isEmpty()) {
            return new RepairResult(rawText, List.of(), List.of());
        }

        proposals.sort(REPAIR_PRIORITY);
        boolean[] occupied = new boolean[rawText.length()];
        List<RepairProposal> selected = new ArrayList<>();
        for (RepairProposal proposal : proposals) {
            if (!overlapsOccupied(occupied, proposal.start, proposal.end)) {
                markOccupied(occupied, proposal.start, proposal.end);
                selected.add(proposal);
            }
        }
        if (selected.isEmpty()) {
            return new RepairResult(rawText, List.of(), List.of());
        }
        selected.sort(Comparator.comparingInt(proposal -> proposal.start));

        StringBuilder repaired = new StringBuilder(rawText.length());
        LinkedHashSet<String> matchedIds = new LinkedHashSet<>();
        int cursor = 0;
        for (RepairProposal proposal : selected) {
            if (proposal.start > cursor) {
                repaired.append(rawText, cursor, proposal.start);
            }
            repaired.append(proposal.target);
            matchedIds.add(IRBaseUtils.reverseLookupArray[proposal.internalId]);
            cursor = proposal.end;
        }
        if (cursor < rawText.length()) {
            repaired.append(rawText, cursor, rawText.length());
        }
        return RepairResult.from(repaired.toString(), matchedIds);
    }

    CommandCandidateRanker.ScoredCandidate selectCandidateByRepairEvidence(
            String rawText,
            CommandCandidateRanker.ScoredCandidate[] ranked,
            CommandCandidateRanker.ScoredCandidate fallback
    ) {
        List<RepairProposal> proposals = collectRepairProposals(rawText, ranked);
        if (proposals.isEmpty()) {
            return fallback;
        }
        proposals.sort(REPAIR_PRIORITY);
        int selectedInternalId = proposals.get(0).internalId;
        for (CommandCandidateRanker.ScoredCandidate candidate : ranked) {
            if (candidate.internalId == selectedInternalId) {
                return candidate;
            }
        }
        return fallback;
    }

    private List<RepairProposal> collectRepairProposals(String rawText, CommandCandidateRanker.ScoredCandidate[] ranked) {
        List<RepairProposal> proposals = new ArrayList<>();
        int limit = Math.min(policy.healMaxCandidates(), ranked.length);
        for (int index = 0; index < limit; index++) {
            CommandCandidateRanker.ScoredCandidate candidate = ranked[index];
            String target = IRBaseUtils.localizedNameArray[candidate.internalId];
            if (target == null || target.isEmpty()) {
                continue;
            }
            RepairProposal proposal = findBestRepairProposal(rawText, candidate.internalId, target, candidate.score);
            if (proposal != null) {
                proposals.add(proposal);
            }
        }
        return proposals;
    }

    private RepairProposal findBestRepairProposal(String rawText, int internalId, String target, double candidateScore) {
        int minimumLength = Math.max(2, target.length() - 1);
        int maximumLength = Math.min(rawText.length(), target.length() + 1);
        String targetPinyin = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(target));
        CommandCandidateRanker.LcsWorkspace workspace = new CommandCandidateRanker.LcsWorkspace();

        int bestStart = -1;
        int bestEnd = -1;
        double bestOverlap = 0.0D;
        int bestLengthDifference = Integer.MAX_VALUE;
        for (int windowLength = minimumLength; windowLength <= maximumLength; windowLength++) {
            for (int start = 0; start <= rawText.length() - windowLength; start++) {
                int end = start + windowLength;
                String slicePinyin = IRBaseUtils.joinTokens(IRBaseUtils.tokenize(rawText.substring(start, end)));
                if (slicePinyin.isEmpty()) {
                    continue;
                }
                double overlap = computeWindowSimilarity(slicePinyin, targetPinyin, workspace);
                int lengthDifference = Math.abs(windowLength - target.length());
                if (overlap > bestOverlap
                        || (Double.compare(overlap, bestOverlap) == 0 && lengthDifference < bestLengthDifference)
                        || (Double.compare(overlap, bestOverlap) == 0 && lengthDifference == bestLengthDifference && (bestStart < 0 || start < bestStart))) {
                    bestOverlap = overlap;
                    bestStart = start;
                    bestEnd = end;
                    bestLengthDifference = lengthDifference;
                }
            }
        }
        return bestOverlap > policy.healPinyinOverlapThreshold() && bestStart >= 0
                ? new RepairProposal(internalId, bestStart, bestEnd, target, bestOverlap, candidateScore)
                : null;
    }

    private double computeWindowSimilarity(String slicePinyin, String targetPinyin, CommandCandidateRanker.LcsWorkspace workspace) {
        double lcsRatio = CommandCandidateRanker.computeLcsRatio(slicePinyin, targetPinyin, workspace);
        double overlapRatio = CommandCandidateRanker.computeCharOverlapRatio(slicePinyin, targetPinyin);
        return (lcsRatio * 0.7D) + (overlapRatio * 0.3D);
    }

    private boolean overlapsOccupied(boolean[] occupied, int start, int end) {
        for (int index = start; index < end && index < occupied.length; index++) {
            if (occupied[index]) {
                return true;
            }
        }
        return false;
    }

    private void markOccupied(boolean[] occupied, int start, int end) {
        for (int index = start; index < end && index < occupied.length; index++) {
            occupied[index] = true;
        }
    }

    static final class RepairResult {
        final String text;
        final List<String> matchedItemRealIds;
        final List<String> matchedEntityTypeIds;

        RepairResult(String text, List<String> matchedItemRealIds, List<String> matchedEntityTypeIds) {
            this.text = text;
            this.matchedItemRealIds = matchedItemRealIds;
            this.matchedEntityTypeIds = matchedEntityTypeIds;
        }

        static RepairResult from(String text, LinkedHashSet<String> objectIds) {
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

    private static final Comparator<RepairProposal> REPAIR_PRIORITY = Comparator
            .comparingDouble((RepairProposal proposal) -> proposal.overlap).reversed()
            .thenComparing(Comparator.comparingInt((RepairProposal proposal) -> proposal.target.length()).reversed())
            .thenComparing(Comparator.comparingDouble((RepairProposal proposal) -> proposal.candidateScore).reversed())
            .thenComparingInt(proposal -> proposal.start);

    private record RepairProposal(int internalId, int start, int end, String target, double overlap, double candidateScore) {
    }
}
