package com.rheinmetal.tianshu.function.intake.repair.core;

import com.rheinmetal.tianshu.api.game.GameObjectId;
import com.rheinmetal.tianshu.function.intake.repair.core.collection.Long2LongOpenHashMap;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The dictionary an utterance is matched against, and the only owner of its index.
 *
 * <p>Rebuild, restore and clear publish a whole new {@link IRIndex} as one reference; parse and lookups
 * read the index that was current when they took the read lock. That is what keeps a rebuild from being
 * observed half-finished by a concurrent parse, and what keeps two service instances independent instead
 * of sharing one set of static arrays.</p>
 */
public final class IRCommandService {
    private final ReadWriteLock lifecycleLock = new ReentrantReadWriteLock();
    private volatile CommandParser parser;
    private volatile IRIndex index = IRIndex.EMPTY;

    public void rebuild(Map<String, List<String>> rawDict) {
        lifecycleLock.writeLock().lock();
        try {
            if (rawDict == null || rawDict.isEmpty()) {
                clearInternal();
                return;
            }
            publish(IndexBuilder.build(rawDict));
        } finally {
            lifecycleLock.writeLock().unlock();
        }
    }

    /**
     * Adopts a cached snapshot.
     *
     * @return {@code false} when the snapshot is not structurally usable, so the caller can discard the
     *         cache file and rebuild instead of matching against a corrupt index
     */
    public boolean restore(IRSnapshot snapshot) {
        lifecycleLock.writeLock().lock();
        try {
            Optional<IRIndex> restored = IRIndex.fromSnapshot(snapshot);
            if (restored.isEmpty()) {
                clearInternal();
                return false;
            }
            publish(restored.get());
            return true;
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
        return parser != null && !index.isEmpty();
    }

    public int getIndexedObjectCount() {
        return index.entryCount();
    }

    public IRParseResult parse(String rawText, ItemContextProvider contextProvider, boolean isFastIR) {
        lifecycleLock.readLock().lock();
        try {
            CommandParser currentParser = parser;
            if (currentParser == null || rawText == null || rawText.isBlank()) {
                return new IRParseResult(currentParser != null, rawText, rawText, List.of());
            }
            Set<Integer> contextIds = contextProvider == null ? Set.of() : contextProvider.getContextInternalIds();
            if (contextIds == null) {
                contextIds = Set.of();
            }
            return currentParser.parse(rawText, contextIds, isFastIR);
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public int resolveInternalId(String realItemId) {
        lifecycleLock.readLock().lock();
        try {
            Integer internalId = resolveInternalIdUnchecked(realItemId, true);
            return internalId == null ? -1 : internalId;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    public String resolveDisplayName(String realItemId) {
        return resolveDisplayName(realItemId, true);
    }

    public String resolveEntityDisplayName(String entityTypeId) {
        return resolveDisplayName(entityTypeId, false);
    }

    private String resolveDisplayName(String rawId, boolean itemPreferred) {
        lifecycleLock.readLock().lock();
        try {
            Integer internalId = resolveInternalIdUnchecked(rawId, itemPreferred);
            return internalId == null ? "" : index.displayName(internalId);
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
                Integer internalId = resolveInternalIdUnchecked(realItemId, true);
                if (internalId != null) {
                    resolved.add(internalId);
                }
            }
            return resolved;
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private Integer resolveInternalIdUnchecked(String rawId, boolean itemPreferred) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        String normalized = rawId.trim();
        Integer direct = index.internalIdFor(normalized);
        if (direct != null) {
            return direct;
        }
        Integer preferred = index.internalIdFor(
                itemPreferred ? GameObjectId.item(normalized) : GameObjectId.entity(normalized));
        if (preferred != null) {
            return preferred;
        }
        return index.internalIdFor(
                itemPreferred ? GameObjectId.entity(normalized) : GameObjectId.item(normalized));
    }

    /** The current index as a serializable snapshot, or {@code null} when nothing is built. */
    public IRSnapshot snapshot(String fingerprint) {
        lifecycleLock.readLock().lock();
        try {
            IRIndex current = index;
            if (!isReady()) {
                return null;
            }
            int directorySize = current.indexDirectory.size();
            long[] directoryKeys = new long[directorySize];
            long[] directoryValues = new long[directorySize];
            int position = 0;
            Long2LongOpenHashMap.EntryIterator iterator = current.indexDirectory.entryIterator();
            while (iterator.next() && position < directorySize) {
                directoryKeys[position] = iterator.key();
                directoryValues[position] = iterator.value();
                position++;
            }
            return new IRSnapshot(
                    fingerprint,
                    current.reverseLookupArray.clone(),
                    current.localizedNameArray.clone(),
                    cloneNested(current.primaryAliasTokensArray),
                    cloneNested(current.fallbackAliasTokensArray),
                    current.indexPool.clone(),
                    directoryKeys,
                    directoryValues
            );
        } finally {
            lifecycleLock.readLock().unlock();
        }
    }

    private void publish(IRIndex next) {
        index = next;
        parser = next.isEmpty() ? null : new CommandParser(next);
    }

    private void clearInternal() {
        index = IRIndex.EMPTY;
        parser = null;
    }

    private static String[][] cloneNested(String[][] source) {
        String[][] clone = new String[source.length][];
        for (int index = 0; index < source.length; index++) {
            clone[index] = source[index] == null ? new String[0] : source[index].clone();
        }
        return clone;
    }
}