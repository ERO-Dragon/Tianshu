package com.rheinmetal.tianshu.function.ir.core.collection;

public final class Long2LongOpenHashMap {
    private static final float LOAD_FACTOR = 0.6f;

    private long[] keys;
    private long[] values;
    private boolean[] used;
    private int mask;
    private int size;
    private int threshold;

    public Long2LongOpenHashMap() {
        this(16);
    }

    public Long2LongOpenHashMap(int expected) {
        int capacity = arraySize(expected);
        keys = new long[capacity];
        values = new long[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    public long get(long key) {
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                return values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return 0L;
    }

    public void put(long key, long value) {
        if (size >= threshold) {
            rehash(keys.length << 1);
        }
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                values[pos] = value;
                return;
            }
            pos = (pos + 1) & mask;
        }
        used[pos] = true;
        keys[pos] = key;
        values[pos] = value;
        size++;
    }

    public int size() {
        return size;
    }

    public EntryIterator entryIterator() {
        return new EntryIterator(keys, values, used);
    }

    private void rehash(int newCapacity) {
        int capacity = arraySize(newCapacity);
        long[] oldKeys = keys;
        long[] oldValues = values;
        boolean[] oldUsed = used;

        keys = new long[capacity];
        values = new long[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
        size = 0;

        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                put(oldKeys[i], oldValues[i]);
            }
        }
    }

    private static int arraySize(int expected) {
        int capacity = 1;
        int needed = Math.max(2, (int) (expected / LOAD_FACTOR) + 1);
        while (capacity < needed) {
            capacity <<= 1;
        }
        return capacity;
    }

    private static int mix(long x) {
        long h = x * 0x9E3779B97F4A7C15L;
        h ^= (h >>> 32);
        return (int) (h ^ (h >>> 16));
    }

    public static final class EntryIterator {
        private final long[] keys;
        private final long[] values;
        private final boolean[] used;
        private int index = -1;

        private EntryIterator(long[] keys, long[] values, boolean[] used) {
            this.keys = keys;
            this.values = values;
            this.used = used;
        }

        public boolean next() {
            while (++index < used.length) {
                if (used[index]) {
                    return true;
                }
            }
            return false;
        }

        public long key() {
            return keys[index];
        }

        public long value() {
            return values[index];
        }
    }
}
