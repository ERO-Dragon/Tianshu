package com.rheinmetal.tianshu.function.intake.repair.core;

import com.rheinmetal.tianshu.function.intake.repair.core.collection.Int2ObjectOpenHashMap;
import com.rheinmetal.tianshu.function.intake.repair.core.collection.IntArrayList;
import com.rheinmetal.tianshu.function.intake.repair.core.collection.IntOpenHashSet;
import com.rheinmetal.tianshu.function.intake.repair.core.collection.Long2LongOpenHashMap;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds one {@link IRIndex} from a dictionary snapshot.
 *
 * <p>The build is a pure function of its input: everything it computes lives in locals and is handed over
 * as one immutable index. Nothing is written to shared static state, so two builds — or a rebuild while a
 * parse is running — cannot interleave inside a half-replaced index.</p>
 */
final class IndexBuilder {

    private IndexBuilder() {
    }

    static IRIndex build(Map<String, List<String>> rawDict) {
        if (rawDict == null || rawDict.isEmpty()) {
            return IRIndex.EMPTY;
        }
        int itemCount = rawDict.size();
        String[] reverseLookup = new String[itemCount];
        String[] localizedNames = new String[itemCount];
        String[][] primaryTokens = new String[itemCount][];
        String[][] fallbackTokens = new String[itemCount][];
        Map<String, Integer> forwardLookup = new LinkedHashMap<>(Math.max(16, itemCount));
        int internalId = 0;
        for (Map.Entry<String, List<String>> entry : rawDict.entrySet()) {
            forwardLookup.put(entry.getKey(), internalId);
            reverseLookup[internalId] = entry.getKey();
            List<String> aliases = entry.getValue();
            if (aliases == null || aliases.isEmpty()) {
                localizedNames[internalId] = "";
                primaryTokens[internalId] = new String[0];
                fallbackTokens[internalId] = new String[0];
            } else {
                localizedNames[internalId] = aliases.get(0);
                primaryTokens[internalId] = IRBaseUtils.tokenize(aliases.get(0));
                fallbackTokens[internalId] = aliases.size() > 1
                        ? IRBaseUtils.tokenize(aliases.get(1))
                        : new String[0];
            }
            internalId++;
        }

        Int2ObjectOpenHashMap<IntOpenHashSet> documentFrequencyBuckets = new Int2ObjectOpenHashMap<>(itemCount * 4);

        // 第一个循环：算词频，双轨喂入
        for (int id = 0; id < itemCount; id++) {
            IntOpenHashSet localSeen = new IntOpenHashSet(32);
            collectDocumentFrequency(primaryTokens[id], id, localSeen, documentFrequencyBuckets);
            collectDocumentFrequency(fallbackTokens[id], id, localSeen, documentFrequencyBuckets);
        }

        IntOpenHashSet stopGramHashes = new IntOpenHashSet(documentFrequencyBuckets.size());
        int threshold = Math.max(1, (int) (itemCount * 0.3d));
        Int2ObjectOpenHashMap.EntryIterator<IntOpenHashSet> dfIterator = documentFrequencyBuckets.entryIterator();
        while (dfIterator.next()) {
            int df = dfIterator.value().size();
            if (df > threshold || df > 5000) {
                stopGramHashes.add(dfIterator.key());
            }
        }

        Int2ObjectOpenHashMap<IntArrayList> tempBuckets = new Int2ObjectOpenHashMap<>(itemCount * 8);
        // 第二个循环：建倒排表，双轨喂入
        for (int id = 0; id < itemCount; id++) {
            collectPostingLists(primaryTokens[id], id, stopGramHashes, tempBuckets);
            collectPostingLists(fallbackTokens[id], id, stopGramHashes, tempBuckets);
        }

        IntArrayList pool = new IntArrayList(Math.max(16, itemCount * 50));
        Long2LongOpenHashMap indexDirectory = new Long2LongOpenHashMap(Math.max(16, itemCount * 8));
        Int2ObjectOpenHashMap.EntryIterator<IntArrayList> bucketIterator = tempBuckets.entryIterator();
        while (bucketIterator.next()) {
            IntArrayList list = bucketIterator.value();
            int size = list.size();
            if (size == 0) {
                continue;
            }
            int startOffset = pool.size();
            for (int index = 0; index < size; index++) {
                pool.add(list.getInt(index));
            }
            long packed = (((long) startOffset) << 32) | (size & 0xffffffffL);
            indexDirectory.put(bucketIterator.key() & 0xffffffffL, packed);
        }

        return new IRIndex(
                reverseLookup,
                localizedNames,
                forwardLookup,
                primaryTokens,
                fallbackTokens,
                pool.toIntArray(),
                indexDirectory
        );
    }

    private static void collectDocumentFrequency(
        String[] tokens,
        int internalId,
        IntOpenHashSet localSeen,
        Int2ObjectOpenHashMap<IntOpenHashSet> documentFrequencyBuckets
    ) {
        int tokenCount = tokens.length;
        for (int gramSize = 2; gramSize <= 3; gramSize++) {
            if (tokenCount < gramSize) {
                continue;
            }
            for (int i = 0; i <= tokenCount - gramSize; i++) {
                int hash = IRBaseUtils.fnv1a32(IRBaseUtils.buildGram(tokens, i, gramSize));
                if (localSeen.add(hash)) {
                    IntOpenHashSet set = documentFrequencyBuckets.computeIfAbsent(hash, ignored -> new IntOpenHashSet(4));
                    set.add(internalId);
                }
            }
        }
    }

    private static void collectPostingLists(
        String[] tokens,
        int internalId,
        IntOpenHashSet stopGramHashes,
        Int2ObjectOpenHashMap<IntArrayList> tempBuckets
    ) {
        int tokenCount = tokens.length;
        IntOpenHashSet localSeen = new IntOpenHashSet(tokenCount * 2 + 4);
        for (int gramSize = 2; gramSize <= 3; gramSize++) {
            if (tokenCount < gramSize) {
                continue;
            }
            for (int i = 0; i <= tokenCount - gramSize; i++) {
                int hash = IRBaseUtils.fnv1a32(IRBaseUtils.buildGram(tokens, i, gramSize));
                if (stopGramHashes.contains(hash) || !localSeen.add(hash)) {
                    continue;
                }
                IntArrayList list = tempBuckets.computeIfAbsent(hash, ignored -> new IntArrayList(8));
                if (list.size() < 200) {
                    list.add(internalId);
                }
            }
        }
    }
}