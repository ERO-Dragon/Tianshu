package com.rheinmetal.tianshu.function.intake.repair.core;

import com.rheinmetal.tianshu.function.intake.repair.core.collection.Long2LongOpenHashMap;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * One built IR index: the dictionary mapping, the tokenized aliases and the inverted gram pool.
 *
 * <p>An index is immutable by construction and published as a single reference, instead of being scattered
 * over static arrays that a second instance could swap underneath a running parse. The arrays are only
 * written by {@link IndexBuilder} while building, then handed over without copying; nothing mutates them
 * afterwards, and {@link IRCommandService} replaces the whole index on rebuild.</p>
 *
 * <p>{@link #fromSnapshot()} validates a deserialized snapshot before it becomes an index; a corrupt cache
 * file must fail here, where it can be discarded and rebuilt, instead of surfacing later as an
 * out-of-bounds read inside matching.</p>
 */
final class IRIndex {
    static final IRIndex EMPTY = new IRIndex(
            new String[0],
            new String[0],
            Map.of(),
            new String[0][],
            new String[0][],
            new int[0],
            new Long2LongOpenHashMap()
    );

    final String[] reverseLookupArray;
    final String[] localizedNameArray;
    final Map<String, Integer> forwardLookupMap;
    final String[][] primaryAliasTokensArray;
    final String[][] fallbackAliasTokensArray;
    final int[] indexPool;
    final Long2LongOpenHashMap indexDirectory;

    IRIndex(
            String[] reverseLookupArray,
            String[] localizedNameArray,
            Map<String, Integer> forwardLookupMap,
            String[][] primaryAliasTokensArray,
            String[][] fallbackAliasTokensArray,
            int[] indexPool,
            Long2LongOpenHashMap indexDirectory
    ) {
        this.reverseLookupArray = reverseLookupArray;
        this.localizedNameArray = localizedNameArray;
        this.forwardLookupMap = forwardLookupMap;
        this.primaryAliasTokensArray = primaryAliasTokensArray;
        this.fallbackAliasTokensArray = fallbackAliasTokensArray;
        this.indexPool = indexPool;
        this.indexDirectory = indexDirectory;
    }

    int entryCount() {
        return reverseLookupArray.length;
    }

    boolean isEmpty() {
        return reverseLookupArray.length == 0;
    }

    String realId(int internalId) {
        return internalId >= 0 && internalId < reverseLookupArray.length ? reverseLookupArray[internalId] : "";
    }

    String displayName(int internalId) {
        if (internalId < 0 || internalId >= localizedNameArray.length) {
            return "";
        }
        String name = localizedNameArray[internalId];
        return name == null ? "" : name;
    }

    String[] primaryTokens(int internalId) {
        return tokensAt(primaryAliasTokensArray, internalId);
    }

    String[] fallbackTokens(int internalId) {
        return tokensAt(fallbackAliasTokensArray, internalId);
    }

    private static String[] tokensAt(String[][] tokens, int internalId) {
        if (internalId < 0 || internalId >= tokens.length || tokens[internalId] == null) {
            return new String[0];
        }
        return tokens[internalId];
    }

    Integer internalIdFor(String key) {
        return forwardLookupMap.get(key);
    }

    /**
     * Turns a snapshot back into an index, or nothing when the snapshot is not structurally usable.
     *
     * <p>The checks are what a cache written by an older or interrupted build needs: every parallel array
     * must have the same length, every packed posting range must lie inside the pool, and every id inside
     * the pool must name a dictionary entry. A snapshot failing any of them is treated as a cache miss.</p>
     */
    static Optional<IRIndex> fromSnapshot(IRSnapshot snapshot) {
        if (snapshot == null) {
            return Optional.empty();
        }
        String[] reverse = snapshot.reverseLookupArray;
        String[] names = snapshot.localizedNameArray;
        String[][] primary = snapshot.primaryAliasTokensArray;
        String[][] fallback = snapshot.fallbackAliasTokensArray;
        int[] pool = snapshot.indexPool;
        long[] keys = snapshot.directoryKeys;
        long[] values = snapshot.directoryValues;
        if (reverse == null || names == null || primary == null || fallback == null || pool == null
                || keys == null || values == null) {
            return Optional.empty();
        }
        int size = reverse.length;
        if (size == 0) {
            return Optional.empty();
        }
        if (names.length != size || primary.length != size || fallback.length != size
                || keys.length != values.length) {
            return Optional.empty();
        }
        for (int internalId = 0; internalId < size; internalId++) {
            if (reverse[internalId] == null || reverse[internalId].isBlank()) {
                return Optional.empty();
            }
        }
        for (int position = 0; position < pool.length; position++) {
            int internalId = pool[position];
            if (internalId < 0 || internalId >= size) {
                return Optional.empty();
            }
        }
        Long2LongOpenHashMap directory = new Long2LongOpenHashMap(Math.max(16, keys.length * 2));
        for (int index = 0; index < keys.length; index++) {
            long packed = values[index];
            int offset = (int) (packed >>> 32);
            int length = (int) packed;
            if (offset < 0 || length < 0 || (long) offset + length > pool.length) {
                return Optional.empty();
            }
            directory.put(keys[index], packed);
        }
        Map<String, Integer> forwardLookup = new LinkedHashMap<>(Math.max(16, size));
        for (int internalId = 0; internalId < size; internalId++) {
            forwardLookup.put(reverse[internalId], internalId);
        }
        return Optional.of(new IRIndex(
                reverse.clone(),
                names.clone(),
                forwardLookup,
                cloneNested(primary),
                cloneNested(fallback),
                pool.clone(),
                directory
        ));
    }

    private static String[][] cloneNested(String[][] source) {
        String[][] clone = new String[source.length][];
        for (int index = 0; index < source.length; index++) {
            clone[index] = source[index] == null ? new String[0] : source[index].clone();
        }
        return clone;
    }
}