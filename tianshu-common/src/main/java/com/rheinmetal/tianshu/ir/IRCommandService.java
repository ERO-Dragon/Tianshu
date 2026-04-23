package com.rheinmetal.tianshu.ir;

import com.rheinmetal.tianshu.ir.collection.Long2LongOpenHashMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

public final class IRCommandService {
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile CommandParser parser;
    private volatile int indexedItemCount;

    public void rebuild(Map<String, String> rawDict) {
        lifecycleLock.writeLock().lock();
        try {
            if (rawDict == null || rawDict.isEmpty()) {
                clearInternal();
                return;
            }
            IndexBuilder.build(rawDict);
            parser = new CommandParser();
            indexedItemCount = rawDict.size();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public void restore(IRSnapshot snapshot) {
        lifecycleLock.writeLock().lock();
        try {
            if (snapshot == null || snapshot.reverseLookupArray.length == 0) {
                clearInternal();
                return;
            }
            applySnapshot(snapshot);
            parser = new CommandParser();
            indexedItemCount = snapshot.reverseLookupArray.length;
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public void clear() {
        lifecycleLock.writeLock().lock();
        try {
            clearInternal();
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    public boolean isReady() {
        return parser != null && indexedItemCount > 0;
    }

    public int getIndexedItemCount() {
        return indexedItemCount;
    }

    public IRParseResult parse(String rawText, ItemContextProvider contextProvider) {
        lifecycleLock.readLock().lock();
        try {
            CommandParser currentParser = parser;
            if (currentParser == null || rawText == null || rawText.isBlank()) {
                return new IRParseResult(currentParser != null, rawText, List.of());
            }
            Set<Integer> contextIds = contextProvider == null ? Set.of() : contextProvider.getContextInternalIds();
            if (contextIds == null) {
                contextIds = Set.of();
            }
            return new IRParseResult(true, rawText, currentParser.parse(rawText, contextIds));
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public int resolveInternalId(String realItemId) {
        lifecycleLock.readLock().lock();
        try {
            Integer internalId = IRBaseUtils.forwardLookupMap.get(realItemId);
            return internalId == null ? -1 : internalId;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public Set<Integer> resolveInternalIds(Collection<String> realItemIds) {
        lifecycleLock.readLock().lock();
        try {
            if (realItemIds == null || realItemIds.isEmpty()) {
                return Set.of();
            }
            Set<Integer> resolved = new HashSet<>(realItemIds.size());
            for (String realItemId : realItemIds) {
                Integer internalId = IRBaseUtils.forwardLookupMap.get(realItemId);
                if (internalId != null) {
                    resolved.add(internalId);
                }
            }
            return resolved;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public IRSnapshot snapshot(String fingerprint) {
        lifecycleLock.readLock().lock();
        try {
            if (!isReady()) {
                return null;
            }
            int entryCount = IndexBuilder.indexDirectory.size();
            long[] directoryKeys = new long[entryCount];
            long[] directoryValues = new long[entryCount];
            int index = 0;
            Long2LongOpenHashMap.EntryIterator iterator = IndexBuilder.indexDirectory.entryIterator();
            while (iterator.next()) {
                directoryKeys[index] = iterator.key();
                directoryValues[index] = iterator.value();
                index++;
            }
            return new IRSnapshot(
                fingerprint,
                IRBaseUtils.reverseLookupArray.clone(),
                IRBaseUtils.localizedNameArray.clone(),
                cloneNested(IndexBuilder.entityTokenArray),
                IndexBuilder.entityJoinedTokenArray.clone(),
                IndexBuilder.entityPinyinLengthArray.clone(),
                copyActiveIndexPool(),
                directoryKeys,
                directoryValues
            );
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private void clearInternal() {
        parser = null;
        indexedItemCount = 0;
        IndexBuilder.INDEX_POOL = new int[0];
        IndexBuilder.writeOffset = 0;
        IndexBuilder.indexDirectory = new Long2LongOpenHashMap();
        IndexBuilder.entityPinyinLengthArray = new int[0];
        IndexBuilder.entityTokenArray = new String[0][];
        IndexBuilder.entityJoinedTokenArray = new String[0];
        IRBaseUtils.reverseLookupArray = new String[0];
        IRBaseUtils.localizedNameArray = new String[0];
        IRBaseUtils.forwardLookupMap = Map.of();
    }

    private void applySnapshot(IRSnapshot snapshot) {
        IRBaseUtils.reverseLookupArray = snapshot.reverseLookupArray.clone();
        IRBaseUtils.localizedNameArray = snapshot.localizedNameArray.clone();
        IRBaseUtils.forwardLookupMap = buildForwardLookup(snapshot.reverseLookupArray);
        IndexBuilder.entityTokenArray = cloneNested(snapshot.entityTokenArray);
        IndexBuilder.entityJoinedTokenArray = snapshot.entityJoinedTokenArray.clone();
        IndexBuilder.entityPinyinLengthArray = snapshot.entityPinyinLengthArray.clone();
        IndexBuilder.INDEX_POOL = snapshot.indexPool.clone();
        IndexBuilder.writeOffset = snapshot.indexPool.length;
        Long2LongOpenHashMap restoredDirectory = new Long2LongOpenHashMap(Math.max(16, snapshot.directoryKeys.length * 2));
        for (int i = 0; i < snapshot.directoryKeys.length; i++) {
            restoredDirectory.put(snapshot.directoryKeys[i], snapshot.directoryValues[i]);
        }
        IndexBuilder.indexDirectory = restoredDirectory;
    }

    private Map<String, Integer> buildForwardLookup(String[] reverseLookupArray) {
        java.util.LinkedHashMap<String, Integer> forwardLookup = new java.util.LinkedHashMap<>(Math.max(16, reverseLookupArray.length));
        for (int i = 0; i < reverseLookupArray.length; i++) {
            forwardLookup.put(reverseLookupArray[i], i);
        }
        return forwardLookup;
    }

    private String[][] cloneNested(String[][] source) {
        String[][] clone = new String[source.length][];
        for (int i = 0; i < source.length; i++) {
            clone[i] = source[i] == null ? new String[0] : source[i].clone();
        }
        return clone;
    }

    private int[] copyActiveIndexPool() {
        int[] activePool = new int[IndexBuilder.writeOffset];
        System.arraycopy(IndexBuilder.INDEX_POOL, 0, activePool, 0, IndexBuilder.writeOffset);
        return activePool;
    }
}
