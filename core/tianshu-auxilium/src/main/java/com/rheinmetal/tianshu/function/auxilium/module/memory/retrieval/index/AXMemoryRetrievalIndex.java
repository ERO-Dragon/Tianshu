package com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.index;

import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXEventVector;
import com.rheinmetal.tianshu.function.auxilium.module.memory.event.AXMemoryEvent;
import com.rheinmetal.tianshu.function.auxilium.module.memory.retrieval.AXMemoryRetrievalPolicy;
import com.rheinmetal.tianshu.function.auxilium.storage.AXHashing;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class AXMemoryRetrievalIndex {
    private final String embeddingNamespace;
    private final AXMemoryRetrievalPolicy policy;
    private final List<EventVectorEntry> entries;
    private final Map<String, String> effectiveMappingByEventId;
    private final List<VectorCluster> l1Clusters;
    private final List<VectorCluster> l2EffectiveMappings;

    private AXMemoryRetrievalIndex(
            String embeddingNamespace,
            AXMemoryRetrievalPolicy policy,
            List<EventVectorEntry> entries,
            Map<String, String> effectiveMappingByEventId,
            List<VectorCluster> l1Clusters,
            List<VectorCluster> l2EffectiveMappings
    ) {
        this.embeddingNamespace = embeddingNamespace == null ? "" : embeddingNamespace;
        this.policy = policy == null ? AXMemoryRetrievalPolicy.DEFAULT : policy;
        this.entries = List.copyOf(entries);
        this.effectiveMappingByEventId = Map.copyOf(effectiveMappingByEventId);
        this.l1Clusters = List.copyOf(l1Clusters);
        this.l2EffectiveMappings = List.copyOf(l2EffectiveMappings);
    }

    public static AXMemoryRetrievalIndex build(List<AXMemoryEvent> events, List<AXEventVector> vectors, String embeddingNamespace) {
        return build(events, vectors, embeddingNamespace, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public static AXMemoryRetrievalIndex build(
            List<AXMemoryEvent> events,
            List<AXEventVector> vectors,
            String embeddingNamespace,
            AXMemoryRetrievalPolicy policy
    ) {
        return build(events, vectors, embeddingNamespace, policy, null);
    }

    public static AXMemoryRetrievalIndex build(
            List<AXMemoryEvent> events,
            List<AXEventVector> vectors,
            String embeddingNamespace,
            AXMemoryRetrievalPolicy policy,
            AXMemoryRetrievalIndexSnapshot snapshot
    ) {
        AXMemoryRetrievalPolicy effectivePolicy = policy == null ? AXMemoryRetrievalPolicy.DEFAULT : policy;
        if (events == null || events.isEmpty() || vectors == null || vectors.isEmpty()) {
            return empty(embeddingNamespace, effectivePolicy);
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
            return empty(embeddingNamespace, effectivePolicy);
        }
        ClusterState clusterState = hydrateClusterState(entries, snapshot);
        if (clusterState == null) {
            clusterState = buildClusterState(entries, effectivePolicy);
        }
        return new AXMemoryRetrievalIndex(
                embeddingNamespace,
                effectivePolicy,
                entries,
                clusterState.effectiveMappingByEventId(),
                clusterState.l1Clusters(),
                clusterState.l2EffectiveMappings()
        );
    }

    private static ClusterState buildClusterState(List<EventVectorEntry> entries, AXMemoryRetrievalPolicy policy) {
        List<VectorCluster> l1Clusters = buildClusters(
                entries,
                "l1",
                policy.l1ClusterThreshold(),
                policy.l1ClusterMaxSize()
        );
        List<VectorCluster> l2Mappings = new ArrayList<>();
        Map<String, String> effectiveMappingByEventId = new HashMap<>();
        for (VectorCluster l1Cluster : l1Clusters) {
            List<VectorCluster> l2Clusters = buildClusters(
                    l1Cluster.entries(),
                    l1Cluster.id() + "_l2",
                    policy.l2EffectiveMappingThreshold(),
                    policy.l2EffectiveMappingMaxSize()
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
        return new ClusterState(l1Clusters, l2Mappings, effectiveMappingByEventId);
    }

    private static ClusterState hydrateClusterState(List<EventVectorEntry> entries, AXMemoryRetrievalIndexSnapshot snapshot) {
        if (entries == null || entries.isEmpty() || snapshot == null || snapshot.l1Clusters().isEmpty()) {
            return null;
        }
        Map<String, EventVectorEntry> entriesByEventId = new LinkedHashMap<>();
        for (EventVectorEntry entry : entries) {
            entriesByEventId.putIfAbsent(entry.event().id(), entry);
        }
        List<VectorCluster> l1Clusters = hydrateClusters(snapshot.l1Clusters(), entriesByEventId);
        List<VectorCluster> l2Mappings = hydrateClusters(snapshot.l2EffectiveMappings(), entriesByEventId);
        if (l1Clusters.isEmpty() || l2Mappings.isEmpty()) {
            return null;
        }
        Map<String, String> effectiveMappingByEventId = new HashMap<>();
        for (Map.Entry<String, String> mapping : snapshot.effectiveMappingByEventId().entrySet()) {
            if (entriesByEventId.containsKey(mapping.getKey()) && mapping.getValue() != null && !mapping.getValue().isBlank()) {
                effectiveMappingByEventId.put(mapping.getKey(), mapping.getValue());
            }
        }
        for (EventVectorEntry entry : entries) {
            effectiveMappingByEventId.putIfAbsent(entry.event().id(), entry.event().id());
        }
        return new ClusterState(l1Clusters, l2Mappings, effectiveMappingByEventId);
    }

    private static List<VectorCluster> hydrateClusters(
            List<AXMemoryRetrievalIndexSnapshot.ClusterSnapshot> snapshots,
            Map<String, EventVectorEntry> entriesByEventId
    ) {
        if (snapshots == null || snapshots.isEmpty() || entriesByEventId == null || entriesByEventId.isEmpty()) {
            return List.of();
        }
        List<VectorCluster> clusters = new ArrayList<>();
        for (AXMemoryRetrievalIndexSnapshot.ClusterSnapshot snapshot : snapshots) {
            if (snapshot == null || snapshot.id().isBlank() || snapshot.eventIds().isEmpty()) {
                continue;
            }
            List<EventVectorEntry> clusterEntries = snapshot.eventIds().stream()
                    .map(entriesByEventId::get)
                    .filter(Objects::nonNull)
                    .toList();
            if (clusterEntries.isEmpty()) {
                continue;
            }
            clusters.add(new VectorCluster(
                    snapshot.id(),
                    clusterEntries,
                    recomputeCentroid(clusterEntries),
                    new LinkedHashSet<>(snapshot.entityTags())
            ));
        }
        return List.copyOf(clusters);
    }

    public static AXMemoryRetrievalIndex empty(String embeddingNamespace) {
        return empty(embeddingNamespace, AXMemoryRetrievalPolicy.DEFAULT);
    }

    public static AXMemoryRetrievalIndex empty(String embeddingNamespace, AXMemoryRetrievalPolicy policy) {
        return new AXMemoryRetrievalIndex(
                embeddingNamespace,
                policy,
                List.of(),
                Map.of(),
                List.of(),
                List.of()
        );
    }

    public boolean isEmpty() {
        return entries.isEmpty();
    }

    public String effectiveMappingId(String eventId) {
        if (eventId == null || eventId.isBlank()) {
            return "";
        }
        return effectiveMappingByEventId.getOrDefault(eventId, eventId);
    }

    public int l1ClusterCount() {
        return l1Clusters.size();
    }

    public int l2EffectiveMappingCount() {
        return l2EffectiveMappings.size();
    }

    public String embeddingNamespace() {
        return embeddingNamespace;
    }

    public Projection projection() {
        return new Projection(
                l2EffectiveMappings.stream()
                        .map(cluster -> new ProjectionCluster(
                                cluster.id(),
                                cluster.entries().stream().map(EventVectorEntry::event).toList(),
                                toFloatArray(cluster.centroid())
                        ))
                        .toList()
        );
    }

    public AXMemoryRetrievalIndexSnapshot toSnapshot(AXMemoryRetrievalIndexCache.SourceStamp sourceStamp) {
        AXMemoryRetrievalIndexCache.SourceStamp stamp = sourceStamp == null
                ? new AXMemoryRetrievalIndexCache.SourceStamp(-1L, -1L, -1L, -1L)
                : sourceStamp;
        return new AXMemoryRetrievalIndexSnapshot(
                AXMemoryRetrievalIndexSnapshot.SCHEMA_VERSION,
                embeddingNamespace,
                System.currentTimeMillis(),
                stamp.eventsSize(),
                stamp.eventsModifiedAtMillis(),
                stamp.vectorsSize(),
                stamp.vectorsModifiedAtMillis(),
                entries.size(),
                entries.size(),
                clusterSnapshots("l1", l1Clusters),
                clusterSnapshots("l2", l2EffectiveMappings),
                effectiveMappingByEventId
        );
    }

    private static List<AXMemoryRetrievalIndexSnapshot.ClusterSnapshot> clusterSnapshots(String level, List<VectorCluster> clusters) {
        if (clusters == null || clusters.isEmpty()) {
            return List.of();
        }
        return clusters.stream()
                .filter(cluster -> cluster != null && !cluster.entries().isEmpty())
                .map(cluster -> new AXMemoryRetrievalIndexSnapshot.ClusterSnapshot(
                        cluster.id(),
                        level,
                        cluster.entries().stream().map(entry -> entry.event().id()).toList(),
                        cluster.entityTags().stream().sorted().toList()
                ))
                .toList();
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

    private static double[] recomputeCentroid(List<EventVectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return new double[0];
        }
        int dimension = entries.get(0).vector().vector().length;
        double[] centroid = new double[dimension];
        int count = 0;
        for (EventVectorEntry entry : entries) {
            if (entry == null || entry.vector().vector().length != dimension) {
                continue;
            }
            float[] vector = entry.vector().vector();
            for (int index = 0; index < dimension; index++) {
                centroid[index] += vector[index];
            }
            count++;
        }
        if (count > 0) {
            for (int index = 0; index < centroid.length; index++) {
                centroid[index] /= count;
            }
        }
        return centroid;
    }

    private static String stableClusterId(String prefix, int index, List<EventVectorEntry> entries) {
        String seed = entries.stream()
                .limit(12)
                .map(entry -> entry.event().id())
                .reduce("", (left, right) -> left + "\n" + right);
        return prefix + "_" + index + "_" + AXHashing.sha256Short(seed + "\n" + entries.size());
    }

    private static String normalizeEntityTag(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.ROOT).replace(' ', '_');
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

    private static float[] toFloatArray(double[] values) {
        if (values == null || values.length == 0) {
            return new float[0];
        }
        float[] result = new float[values.length];
        for (int index = 0; index < values.length; index++) {
            result[index] = (float) values[index];
        }
        return result;
    }

    public record EventVectorEntry(AXMemoryEvent event, AXEventVector vector) {
    }

    public record Projection(List<ProjectionCluster> l2Clusters) {
        public Projection {
            l2Clusters = l2Clusters == null ? List.of() : List.copyOf(l2Clusters);
        }
    }

    public record ProjectionCluster(String id, List<AXMemoryEvent> events, float[] centroid) {
        public ProjectionCluster {
            id = id == null ? "" : id.trim();
            events = events == null ? List.of() : List.copyOf(events);
            centroid = centroid == null ? new float[0] : centroid.clone();
        }
    }

    private record ClusterState(
            List<VectorCluster> l1Clusters,
            List<VectorCluster> l2EffectiveMappings,
            Map<String, String> effectiveMappingByEventId
    ) {
        private ClusterState {
            l1Clusters = l1Clusters == null ? List.of() : List.copyOf(l1Clusters);
            l2EffectiveMappings = l2EffectiveMappings == null ? List.of() : List.copyOf(l2EffectiveMappings);
            effectiveMappingByEventId = effectiveMappingByEventId == null ? Map.of() : Map.copyOf(effectiveMappingByEventId);
        }
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
