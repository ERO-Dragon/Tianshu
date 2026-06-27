package com.rheinmetal.tianshu.function.auxilium.memory;

import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class AXMemoryRetrievalIndex {
    private static final double L1_CLUSTER_THRESHOLD = 0.56D;
    private static final double L2_EFFECTIVE_MAPPING_THRESHOLD = 0.92D;
    private static final int L1_CLUSTER_MAX_SIZE = 256;
    private static final int L2_MAPPING_MAX_SIZE = 48;
    private static final int DEFAULT_MAX_ROUTED_CANDIDATES = 4096;
    private static final int MIN_ROUTED_L1_CLUSTERS = 4;

    private final String embeddingNamespace;
    private final List<EventVectorEntry> entries;
    private final Map<String, List<EventVectorEntry>> entriesByEntityTag;
    private final Map<String, Set<String>> relatedEntityTags;
    private final Map<String, String> effectiveMappingByEventId;
    private final List<VectorCluster> l1Clusters;
    private final List<VectorCluster> l2EffectiveMappings;

    private AXMemoryRetrievalIndex(
            String embeddingNamespace,
            List<EventVectorEntry> entries,
            Map<String, List<EventVectorEntry>> entriesByEntityTag,
            Map<String, Set<String>> relatedEntityTags,
            Map<String, String> effectiveMappingByEventId,
            List<VectorCluster> l1Clusters,
            List<VectorCluster> l2EffectiveMappings
    ) {
        this.embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace;
        this.entries = List.copyOf(entries);
        this.entriesByEntityTag = copyListMap(entriesByEntityTag);
        this.relatedEntityTags = copySetMap(relatedEntityTags);
        this.effectiveMappingByEventId = Map.copyOf(effectiveMappingByEventId);
        this.l1Clusters = List.copyOf(l1Clusters);
        this.l2EffectiveMappings = List.copyOf(l2EffectiveMappings);
    }

    static AXMemoryRetrievalIndex build(List<AXMemoryEvent> events, List<AXEventVector> vectors, String embeddingNamespace) {
        if (events == null || events.isEmpty() || vectors == null || vectors.isEmpty()) {
            return empty(embeddingNamespace);
        }
        Map<String, AXMemoryEvent> eventsById = new LinkedHashMap<>();
        for (AXMemoryEvent event : events) {
            if (event != null && !event.isEmpty()) {
                eventsById.putIfAbsent(event.id(), event);
            }
        }
        List<EventVectorEntry> entries = new ArrayList<>();
        for (AXEventVector vector : vectors) {
            if (vector == null || vector.isEmpty()) {
                continue;
            }
            if (embeddingNamespace != null && !embeddingNamespace.isBlank() && !embeddingNamespace.equals(vector.embeddingNamespace())) {
                continue;
            }
            AXMemoryEvent event = eventsById.get(vector.eventId());
            if (event == null || event.stmId().isBlank() || !event.factHash().equals(vector.eventFactHash())) {
                continue;
            }
            if (vector.vector().length != vector.dimension()) {
                continue;
            }
            entries.add(new EventVectorEntry(event, vector));
        }
        if (entries.isEmpty()) {
            return empty(embeddingNamespace);
        }
        Map<String, List<EventVectorEntry>> entriesByEntityTag = buildEntityIndex(entries);
        Map<String, Set<String>> relatedEntityTags = buildEntityGraph(entries);
        List<VectorCluster> l1Clusters = buildClusters(entries, "l1", L1_CLUSTER_THRESHOLD, L1_CLUSTER_MAX_SIZE);
        List<VectorCluster> l2Mappings = new ArrayList<>();
        Map<String, String> effectiveMappingByEventId = new HashMap<>();
        for (VectorCluster l1Cluster : l1Clusters) {
            List<VectorCluster> l2Clusters = buildClusters(
                    l1Cluster.entries(),
                    l1Cluster.id() + "_l2",
                    L2_EFFECTIVE_MAPPING_THRESHOLD,
                    L2_MAPPING_MAX_SIZE
            );
            l2Mappings.addAll(l2Clusters);
            for (VectorCluster l2Cluster : l2Clusters) {
                for (EventVectorEntry entry : l2Cluster.entries()) {
                    effectiveMappingByEventId.put(entry.event().id(), l2Cluster.id());
                }
            }
        }
        for (EventVectorEntry entry : entries) {
            effectiveMappingByEventId.putIfAbsent(entry.event().id(), entry.event().id());
        }
        return new AXMemoryRetrievalIndex(
                embeddingNamespace,
                entries,
                entriesByEntityTag,
                relatedEntityTags,
                effectiveMappingByEventId,
                l1Clusters,
                l2Mappings
        );
    }

    static AXMemoryRetrievalIndex empty(String embeddingNamespace) {
        return new AXMemoryRetrievalIndex(
                embeddingNamespace,
                List.of(),
                Map.of(),
                Map.of(),
                Map.of(),
                List.of(),
                List.of()
        );
    }

    boolean isEmpty() {
        return entries.isEmpty();
    }

    RoutedCandidates route(String queryText, float[] queryVector) {
        QueryAnalysis analysis = analyze(queryText);
        if (entries.isEmpty() || queryVector == null || queryVector.length == 0) {
            return new RoutedCandidates(List.of(), analysis);
        }
        int maxCandidates = Math.max(DEFAULT_MAX_ROUTED_CANDIDATES, MIN_ROUTED_L1_CLUSTERS * L1_CLUSTER_MAX_SIZE);
        LinkedHashSet<EventVectorEntry> routed = new LinkedHashSet<>();
        for (String tag : analysis.matchedEntityTags()) {
            List<EventVectorEntry> tagged = entriesByEntityTag.get(tag);
            if (tagged != null) {
                routed.addAll(tagged);
            }
        }
        if (entries.size() <= maxCandidates) {
            routed.addAll(entries);
            return new RoutedCandidates(List.copyOf(routed), analysis);
        }
        List<ScoredCluster> clusters = l1Clusters.stream()
                .map(cluster -> new ScoredCluster(cluster, routeScore(cluster, queryVector, analysis)))
                .sorted(Comparator.comparingDouble(ScoredCluster::score).reversed())
                .toList();
        int selectedClusters = 0;
        for (ScoredCluster scored : clusters) {
            if (routed.size() >= maxCandidates) {
                break;
            }
            if (selectedClusters >= MIN_ROUTED_L1_CLUSTERS && scored.score() <= 0.20D) {
                continue;
            }
            routed.addAll(scored.cluster().entries());
            selectedClusters++;
        }
        if (routed.isEmpty()) {
            entries.stream().limit(maxCandidates).forEach(routed::add);
        }
        return new RoutedCandidates(routed.stream().limit(maxCandidates).toList(), analysis);
    }

    double score(EventVectorEntry entry, float[] queryVector, QueryAnalysis analysis, long nowMillis) {
        if (entry == null || queryVector == null || queryVector.length == 0) {
            return 0.0D;
        }
        double semantic = cosine(queryVector, entry.vector().vector());
        if (semantic <= 0.0D) {
            return 0.0D;
        }
        return semantic
                * entityWeight(entry.event(), analysis)
                * timeWeight(entry.event(), nowMillis)
                * spatialWeight(entry.event(), analysis);
    }

    String effectiveMappingId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return "";
        }
        return effectiveMappingByEventId.getOrDefault(eventId, eventId);
    }

    int l1ClusterCount() {
        return l1Clusters.size();
    }

    int l2EffectiveMappingCount() {
        return l2EffectiveMappings.size();
    }

    String embeddingNamespace() {
        return embeddingNamespace;
    }

    private QueryAnalysis analyze(String queryText) {
        String normalizedQuery = normalize(queryText);
        Set<String> matchedTags = new LinkedHashSet<>();
        if (!normalizedQuery.isBlank()) {
            for (String tag : entriesByEntityTag.keySet()) {
                if (entityTagMatches(normalizedQuery, tag)) {
                    matchedTags.add(tag);
                }
            }
        }
        Set<String> relatedTags = new LinkedHashSet<>();
        for (String tag : matchedTags) {
            relatedTags.addAll(relatedEntityTags.getOrDefault(tag, Set.of()));
        }
        relatedTags.removeAll(matchedTags);
        return new QueryAnalysis(
                Set.copyOf(matchedTags),
                Set.copyOf(relatedTags),
                AXMemoryPosition.parse(queryText).orElse(null)
        );
    }

    private double routeScore(VectorCluster cluster, float[] queryVector, QueryAnalysis analysis) {
        double semantic = cosine(queryVector, cluster.centroid());
        double entity = 0.0D;
        for (String tag : cluster.entityTags()) {
            if (analysis.matchedEntityTags().contains(tag)) {
                entity = Math.max(entity, 0.18D);
            } else if (analysis.relatedEntityTags().contains(tag)) {
                entity = Math.max(entity, 0.06D);
            }
        }
        return semantic + entity;
    }

    private static double entityWeight(AXMemoryEvent event, QueryAnalysis analysis) {
        if (event == null || analysis == null || event.entityTags().isEmpty()) {
            return 1.0D;
        }
        double weight = 1.0D;
        for (String rawTag : event.entityTags()) {
            String tag = normalizeEntityTag(rawTag);
            if (analysis.matchedEntityTags().contains(tag)) {
                weight = Math.max(weight, 1.25D);
            } else if (analysis.relatedEntityTags().contains(tag)) {
                weight = Math.max(weight, 1.08D);
            }
        }
        return weight;
    }

    private static double timeWeight(AXMemoryEvent event, long nowMillis) {
        if (event == null || nowMillis <= 0L || event.happenedAtMillis() <= 0L) {
            return 1.0D;
        }
        long ageMillis = Math.max(0L, nowMillis - event.happenedAtMillis());
        double ageDays = ageMillis / 86_400_000.0D;
        return Math.max(0.90D, 1.0D - Math.min(0.10D, ageDays / 3650.0D));
    }

    private static double spatialWeight(AXMemoryEvent event, QueryAnalysis analysis) {
        if (event == null || analysis == null || analysis.queryPosition().isEmpty() || !event.spatiallyBound()) {
            return 1.0D;
        }
        Optional<AXMemoryPosition> eventPosition = AXMemoryPosition.parse(event.position());
        if (eventPosition.isEmpty()) {
            return 1.0D;
        }
        double distance = eventPosition.get().distanceTo(analysis.queryPosition().get());
        if (distance <= 64.0D) {
            return 1.0D;
        }
        if (distance <= 256.0D) {
            return 0.96D;
        }
        if (distance <= 1024.0D) {
            return 0.88D;
        }
        return 0.76D;
    }

    private static Map<String, List<EventVectorEntry>> buildEntityIndex(List<EventVectorEntry> entries) {
        Map<String, List<EventVectorEntry>> result = new LinkedHashMap<>();
        for (EventVectorEntry entry : entries) {
            for (String rawTag : entry.event().entityTags()) {
                String tag = normalizeEntityTag(rawTag);
                if (!tag.isBlank()) {
                    result.computeIfAbsent(tag, ignored -> new ArrayList<>()).add(entry);
                }
            }
        }
        return result;
    }

    private static Map<String, Set<String>> buildEntityGraph(List<EventVectorEntry> entries) {
        Map<String, Set<String>> tagsByStm = new LinkedHashMap<>();
        for (EventVectorEntry entry : entries) {
            Set<String> tags = tagsByStm.computeIfAbsent(entry.event().stmId(), ignored -> new LinkedHashSet<>());
            for (String rawTag : entry.event().entityTags()) {
                String tag = normalizeEntityTag(rawTag);
                if (!tag.isBlank()) {
                    tags.add(tag);
                }
            }
        }
        Map<String, Set<String>> graph = new LinkedHashMap<>();
        for (Set<String> tags : tagsByStm.values()) {
            for (String tag : tags) {
                Set<String> related = graph.computeIfAbsent(tag, ignored -> new LinkedHashSet<>());
                for (String other : tags) {
                    if (!Objects.equals(tag, other)) {
                        related.add(other);
                    }
                }
            }
        }
        return graph;
    }

    private static List<VectorCluster> buildClusters(List<EventVectorEntry> sourceEntries, String prefix, double threshold, int maxSize) {
        if (sourceEntries == null || sourceEntries.isEmpty()) {
            return List.of();
        }
        List<MutableCluster> clusters = new ArrayList<>();
        for (EventVectorEntry entry : sourceEntries) {
            MutableCluster best = null;
            double bestScore = threshold;
            for (MutableCluster cluster : clusters) {
                if (cluster.size() >= maxSize) {
                    continue;
                }
                double score = cosine(entry.vector().vector(), cluster.centroid());
                if (score >= bestScore) {
                    bestScore = score;
                    best = cluster;
                }
            }
            if (best == null) {
                best = new MutableCluster();
                clusters.add(best);
            }
            best.add(entry);
        }
        List<VectorCluster> result = new ArrayList<>();
        for (int index = 0; index < clusters.size(); index++) {
            MutableCluster cluster = clusters.get(index);
            result.add(cluster.freeze(stableClusterId(prefix, index, cluster.entries())));
        }
        return result;
    }

    private static String stableClusterId(String prefix, int index, List<EventVectorEntry> entries) {
        String seed = entries.stream()
                .limit(12)
                .map(entry -> entry.event().id())
                .reduce("", (left, right) -> left + "\n" + right);
        return prefix + "_" + index + "_" + AXHashing.sha256Short(seed + "\n" + entries.size());
    }

    private static boolean entityTagMatches(String normalizedQuery, String normalizedTag) {
        if (normalizedQuery.isBlank() || normalizedTag.isBlank()) {
            return false;
        }
        if (normalizedQuery.contains(normalizedTag.replace(':', ' ')) || normalizedQuery.contains(normalizedTag)) {
            return true;
        }
        List<String> parts = entityParts(normalizedTag);
        if (parts.isEmpty()) {
            return false;
        }
        int matches = 0;
        for (String part : parts) {
            if (normalizedQuery.contains(part)) {
                matches++;
            }
        }
        return matches == parts.size() || (parts.size() > 1 && matches >= Math.min(2, parts.size()));
    }

    private static List<String> entityParts(String normalizedTag) {
        String tag = normalizedTag;
        int colon = tag.indexOf(':');
        if (colon >= 0 && colon + 1 < tag.length()) {
            tag = tag.substring(colon + 1);
        }
        List<String> parts = new ArrayList<>();
        for (String part : tag.split("[^a-z0-9]+")) {
            if (part.length() >= 3 && !"minecraft".equals(part)) {
                parts.add(part);
            }
        }
        return parts;
    }

    private static String normalizeEntityTag(String value) {
        return normalize(value).replace(' ', '_');
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static double cosine(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 0.0D || rightNorm <= 0.0D) {
            return 0.0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static double cosine(float[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0.0D;
        }
        double dot = 0.0D;
        double leftNorm = 0.0D;
        double rightNorm = 0.0D;
        for (int i = 0; i < left.length; i++) {
            dot += left[i] * right[i];
            leftNorm += left[i] * left[i];
            rightNorm += right[i] * right[i];
        }
        if (leftNorm <= 0.0D || rightNorm <= 0.0D) {
            return 0.0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    private static Map<String, List<EventVectorEntry>> copyListMap(Map<String, List<EventVectorEntry>> source) {
        Map<String, List<EventVectorEntry>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
        return Map.copyOf(copy);
    }

    private static Map<String, Set<String>> copySetMap(Map<String, Set<String>> source) {
        Map<String, Set<String>> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, Set.copyOf(value)));
        return Map.copyOf(copy);
    }

    record EventVectorEntry(AXMemoryEvent event, AXEventVector vector) {
    }

    record RoutedCandidates(List<EventVectorEntry> entries, QueryAnalysis analysis) {
        RoutedCandidates {
            entries = entries == null ? List.of() : List.copyOf(entries);
            analysis = analysis == null ? QueryAnalysis.empty() : analysis;
        }
    }

    record QueryAnalysis(Set<String> matchedEntityTags, Set<String> relatedEntityTags, AXMemoryPosition position) {
        QueryAnalysis {
            matchedEntityTags = matchedEntityTags == null ? Set.of() : Set.copyOf(matchedEntityTags);
            relatedEntityTags = relatedEntityTags == null ? Set.of() : Set.copyOf(relatedEntityTags);
        }

        static QueryAnalysis empty() {
            return new QueryAnalysis(Set.of(), Set.of(), null);
        }

        Optional<AXMemoryPosition> queryPosition() {
            return Optional.ofNullable(position);
        }
    }

    private record ScoredCluster(VectorCluster cluster, double score) {
    }

    private record VectorCluster(String id, List<EventVectorEntry> entries, double[] centroid, Set<String> entityTags) {
        VectorCluster {
            entries = entries == null ? List.of() : List.copyOf(entries);
            centroid = centroid == null ? new double[0] : centroid.clone();
            entityTags = entityTags == null ? Set.of() : Set.copyOf(entityTags);
        }
    }

    private static final class MutableCluster {
        private final List<EventVectorEntry> entries = new ArrayList<>();
        private double[] centroid = new double[0];

        private void add(EventVectorEntry entry) {
            if (entry == null) {
                return;
            }
            entries.add(entry);
            recomputeCentroid();
        }

        private int size() {
            return entries.size();
        }

        private double[] centroid() {
            return centroid;
        }

        private List<EventVectorEntry> entries() {
            return entries;
        }

        private VectorCluster freeze(String id) {
            Set<String> tags = new HashSet<>();
            for (EventVectorEntry entry : entries) {
                for (String rawTag : entry.event().entityTags()) {
                    String tag = normalizeEntityTag(rawTag);
                    if (!tag.isBlank()) {
                        tags.add(tag);
                    }
                }
            }
            return new VectorCluster(id, entries, centroid, tags);
        }

        private void recomputeCentroid() {
            if (entries.isEmpty()) {
                centroid = new double[0];
                return;
            }
            int dimension = entries.get(0).vector().vector().length;
            double[] next = new double[dimension];
            int count = 0;
            for (EventVectorEntry entry : entries) {
                float[] vector = entry.vector().vector();
                if (vector.length != dimension) {
                    continue;
                }
                for (int i = 0; i < dimension; i++) {
                    next[i] += vector[i];
                }
                count++;
            }
            if (count > 0) {
                for (int i = 0; i < next.length; i++) {
                    next[i] /= count;
                }
            }
            centroid = next;
        }
    }
}
