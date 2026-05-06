package com.rheinmetal.tianshu.function.ir.core;

import com.rheinmetal.tianshu.function.ir.core.collection.Int2ObjectOpenHashMap;
import com.rheinmetal.tianshu.function.ir.core.collection.IntArrayList;
import com.rheinmetal.tianshu.function.ir.core.collection.IntOpenHashSet;
import com.rheinmetal.tianshu.function.ir.core.collection.Long2LongOpenHashMap;

import java.util.List;
import java.util.Map;

public final class IndexBuilder {

    public static int[] INDEX_POOL = new int[0];
    public static int writeOffset = 0;
    public static Long2LongOpenHashMap indexDirectory = new Long2LongOpenHashMap();

    private IndexBuilder() {
    }

    public static void build(Map<String, List<String>> rawDict) {
        int itemCount = IRBaseUtils.buildMapping(rawDict);
        INDEX_POOL = new int[Math.max(16, itemCount * 50)];
        writeOffset = 0;
        indexDirectory = new Long2LongOpenHashMap(Math.max(16, itemCount * 8));

        Int2ObjectOpenHashMap<IntOpenHashSet> documentFrequencyBuckets = new Int2ObjectOpenHashMap<>(itemCount * 4);

        // 第一个循环：算词频，双轨喂入
        for (int internalId = 0; internalId < itemCount; internalId++) {
            IntOpenHashSet localSeen = new IntOpenHashSet(32);
            collectDocumentFrequency(IRBaseUtils.primaryAliasTokensArray[internalId], internalId, localSeen, documentFrequencyBuckets);
            collectDocumentFrequency(IRBaseUtils.fallbackAliasTokensArray[internalId], internalId, localSeen, documentFrequencyBuckets);
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
        for (int internalId = 0; internalId < itemCount; internalId++) {
            collectPostingLists(IRBaseUtils.primaryAliasTokensArray[internalId], internalId, stopGramHashes, tempBuckets);
            collectPostingLists(IRBaseUtils.fallbackAliasTokensArray[internalId], internalId, stopGramHashes, tempBuckets);
        }

        Int2ObjectOpenHashMap.EntryIterator<IntArrayList> bucketIterator = tempBuckets.entryIterator();
        while (bucketIterator.next()) {
            IntArrayList list = bucketIterator.value();
            int size = list.size();
            if (size == 0) {
                continue;
            }
            int startOffset = writeOffset;
            ensureCapacity(writeOffset + size);
            System.arraycopy(list.elements(), 0, INDEX_POOL, writeOffset, size);
            writeOffset += size;
            long packed = (((long) startOffset) << 32) | (size & 0xffffffffL);
            indexDirectory.put(bucketIterator.key() & 0xffffffffL, packed);
        }

        tempBuckets = null;
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

    private static void ensureCapacity(int requiredCapacity) {
        if (requiredCapacity <= INDEX_POOL.length) {
            return;
        }
        int newCapacity = INDEX_POOL.length + (INDEX_POOL.length >> 1) + 1;
        if (newCapacity < requiredCapacity) {
            newCapacity = requiredCapacity;
        }
        int[] newPool = new int[newCapacity];
        System.arraycopy(INDEX_POOL, 0, newPool, 0, writeOffset);
        INDEX_POOL = newPool;
    }
}
