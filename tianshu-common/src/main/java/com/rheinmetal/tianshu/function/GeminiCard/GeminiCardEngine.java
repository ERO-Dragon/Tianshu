package com.rheinmetal.tianshu.function.GeminiCard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

public final class GeminiCardEngine {

    private static final long ANALYSIS_CACHE_TTL_MS = 30_000L;
    private static final int MAX_LAYER2_DIFF_LINES = 5;

    private GeminiCardLlmBridge llmBridge = new GeminiCardNoopLlmBridge();
    private String activeSemanticKey = "";
    private long activeSinceMs = 0L;
    private GeminiCardPendingRequest pendingRequest;
    private final Map<String, CachedAnalysis> analysisCache = new HashMap<>();

    public List<GeminiCardLine> buildLines(GeminiCardContext context) {
        if (context == null || !context.enabled() || context.hoveredItem() == null || context.hoveredItem().empty()) {
            killActiveSession();
            return List.of();
        }

        GeminiCardItemData hovered = context.hoveredItem();
        if (!Objects.equals(activeSemanticKey, hovered.semanticKey())) {
            killActiveSession();
            activeSemanticKey = hovered.semanticKey();
            activeSinceMs = System.currentTimeMillis();
        }

        List<GeminiCardLine> lines = new ArrayList<>();
        lines.add(new GeminiCardLine("[Tab+左键] 查看来源 | [Tab+右键] 查看用途", GeminiCardLineTone.MUTED));
        appendLayer2(lines, hovered, context.comparison());
        appendLayer3(lines, hovered, context.comparison());
        return lines;
    }

    public void setLlmBridge(GeminiCardLlmBridge llmBridge) {
        this.llmBridge = llmBridge == null ? new GeminiCardNoopLlmBridge() : llmBridge;
    }

    public void killActiveSession() {
        activeSemanticKey = "";
        activeSinceMs = 0L;
        if (pendingRequest != null) {
            pendingRequest.cancel();
            pendingRequest = null;
        }
    }

    private void appendLayer2(List<GeminiCardLine> lines, GeminiCardItemData hovered, GeminiCardComparisonData comparison) {
        if (comparison == null || comparison.item() == null || comparison.item().empty()) return;
        GeminiCardItemData equipped = comparison.item();
        if (!isSamePrimaryType(hovered, equipped)) return;

        List<GeminiCardLine> diffLines = new ArrayList<>();
        appendNumericDiff(diffLines, "攻击", hovered.attackDamage(), equipped.attackDamage());
        appendNumericDiff(diffLines, "攻速", hovered.attackSpeed(), equipped.attackSpeed());
        appendNumericDiff(diffLines, "护甲", hovered.armor(), equipped.armor());
        appendDurabilityDiff(diffLines, hovered, equipped);
        appendEnchantmentDiff(diffLines, hovered.enchantments(), equipped.enchantments());

        if (!diffLines.isEmpty()) {
            lines.add(new GeminiCardLine("对比:" + comparison.label(), GeminiCardLineTone.HEADER));
            appendLimitedDiffLines(lines, diffLines);
        }
    }

    private static void appendLimitedDiffLines(List<GeminiCardLine> lines, List<GeminiCardLine> diffLines) {
        int limit = Math.min(diffLines.size(), MAX_LAYER2_DIFF_LINES);
        for (int i = 0; i < limit; i++) {
            lines.add(diffLines.get(i));
        }
        int hidden = diffLines.size() - limit;
        if (hidden > 0) {
            lines.add(new GeminiCardLine("其余差异已折叠：" + hidden + " 项", GeminiCardLineTone.MUTED));
        }
    }

    private void appendLayer3(List<GeminiCardLine> lines, GeminiCardItemData hovered, GeminiCardComparisonData comparison) {
        if (!llmBridge.isConfigured()) return;
        if (comparison != null && comparison.item() != null && isSamePrimaryType(hovered, comparison.item())) {
            if (System.currentTimeMillis() - activeSinceMs >= 2000L) {
                GeminiCardAnalysisResult result = getCachedOrRequestAnalysis(comparison.item(), hovered);
                if (result.unavailable()) {
                    lines.add(new GeminiCardLine("⚪ 深度分析暂不可用", GeminiCardLineTone.MUTED));
                } else if (!result.text().isBlank()) {
                    lines.add(new GeminiCardLine(result.text(), GeminiCardLineTone.MUTED));
                }
            } else {
                lines.add(new GeminiCardLine("⏳ 停留2秒分析差异...", GeminiCardLineTone.MUTED));
            }
        }
        lines.add(new GeminiCardLine("💬 说出\"介绍下这个\"查看百科", GeminiCardLineTone.MUTED));
    }

    private GeminiCardAnalysisResult getCachedOrRequestAnalysis(GeminiCardItemData equipped, GeminiCardItemData hovered) {
        GeminiCardAnalysisRequest request = GeminiCardAnalysisRequest.difference(equipped, hovered);
        long now = System.currentTimeMillis();
        CachedAnalysis cached = analysisCache.get(request.semanticKey());
        if (cached != null && now - cached.createdAtMs() <= ANALYSIS_CACHE_TTL_MS) {
            return cached.result();
        }
        GeminiCardAnalysisResult result = llmBridge.requestDifferenceAnalysis(request);
        analysisCache.put(request.semanticKey(), new CachedAnalysis(result, now));
        return result;
    }

    private static boolean isSamePrimaryType(GeminiCardItemData a, GeminiCardItemData b) {
        if (a == null || b == null || a.empty() || b.empty()) return false;
        if (a.kind() == GeminiCardItemKind.OTHER || b.kind() == GeminiCardItemKind.OTHER) return false;
        return a.kind() == b.kind();
    }

    private static void appendNumericDiff(List<GeminiCardLine> lines, String label, double hoveredValue, double equippedValue) {
        double diff = hoveredValue - equippedValue;
        if (Math.abs(diff) < 0.001D) return;
        String arrow = diff > 0 ? "↑" : "↓";
        lines.add(new GeminiCardLine(label + " " + arrow + formatSigned(diff), diff > 0 ? GeminiCardLineTone.POSITIVE : GeminiCardLineTone.NEGATIVE));
    }

    private static void appendDurabilityDiff(List<GeminiCardLine> lines, GeminiCardItemData hovered, GeminiCardItemData equipped) {
        if (!hovered.damageable() || !equipped.damageable()) return;
        int diff = hovered.durabilityLeft() - equipped.durabilityLeft();
        if (diff == 0) return;
        String arrow = diff > 0 ? "↑" : "↓";
        lines.add(new GeminiCardLine("耐久 " + arrow + (diff > 0 ? "+" : "") + diff, diff > 0 ? GeminiCardLineTone.POSITIVE : GeminiCardLineTone.NEGATIVE));
    }

    private static void appendEnchantmentDiff(List<GeminiCardLine> lines, Map<String, Integer> hovered, Map<String, Integer> equipped) {
        if (hovered == null || equipped == null) return;
        for (Map.Entry<String, Integer> entry : hovered.entrySet()) {
            int equippedLevel = equipped.getOrDefault(entry.getKey(), 0);
            int diff = entry.getValue() - equippedLevel;
            if (diff == 0) continue;
            String arrow = diff > 0 ? "↑" : "↓";
            lines.add(new GeminiCardLine(shortName(entry.getKey()) + " " + arrow + (diff > 0 ? "+" : "") + diff, diff > 0 ? GeminiCardLineTone.POSITIVE : GeminiCardLineTone.NEGATIVE));
        }
        for (Map.Entry<String, Integer> entry : equipped.entrySet()) {
            if (hovered.containsKey(entry.getKey())) continue;
            lines.add(new GeminiCardLine(shortName(entry.getKey()) + " ↓-" + entry.getValue(), GeminiCardLineTone.NEGATIVE));
        }
    }

    private static String formatSigned(double value) {
        String formatted = Math.abs(value - Math.rint(value)) < 0.001D
                ? String.valueOf((int) value)
                : String.format(java.util.Locale.ROOT, "%.2f", value);
        return value > 0 ? "+" + formatted : formatted;
    }

    private static String shortName(String id) {
        int index = id.lastIndexOf(':');
        return index >= 0 ? id.substring(index + 1) : id;
    }

    private record CachedAnalysis(GeminiCardAnalysisResult result, long createdAtMs) {
    }

    private static final class GeminiCardPendingRequest {
        private final CompletableFuture<?> future;

        private GeminiCardPendingRequest(CompletableFuture<?> future) {
            this.future = future;
        }

        private void cancel() {
            if (future != null) {
                future.cancel(true);
            }
        }
    }
}
