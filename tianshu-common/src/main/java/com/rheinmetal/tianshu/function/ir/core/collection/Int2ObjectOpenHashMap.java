package com.rheinmetal.tianshu.function.ir.core.collection;

import java.util.Arrays;
import java.util.function.IntFunction;

public final class Int2ObjectOpenHashMap<V> {
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private Object[] values;
    private boolean[] used;
    private int mask;
    private int size;
    private int threshold;

    public Int2ObjectOpenHashMap() {
        this(16);
    }

    public Int2ObjectOpenHashMap(int expected) {
        int capacity = arraySize(expected);
        keys = new int[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    @SuppressWarnings("unchecked")
    public V get(int key) {
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                return (V) values[pos];
            }
            pos = (pos + 1) & mask;
        }
        return null;
    }

    public V put(int key, V value) {
        if (size >= threshold) {
            rehash(keys.length << 1);
        }
        return insert(key, value);
    }

    @SuppressWarnings("unchecked")
    public V computeIfAbsent(int key, IntFunction<? extends V> mappingFunction) {
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                return (V) values[pos];
            }
            pos = (pos + 1) & mask;
        }
        if (size >= threshold) {
            rehash(keys.length << 1);
            return computeIfAbsent(key, mappingFunction);
        }
        V value = mappingFunction.apply(key);
        used[pos] = true;
        keys[pos] = key;
        values[pos] = value;
        size++;
        return value;
    }

    public int size() {
        return size;
    }

    public EntryIterator<V> entryIterator() {
        return new EntryIterator<>(keys, values, used);
    }

    @SuppressWarnings("unchecked")
    private V insert(int key, V value) {
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                V old = (V) values[pos];
                values[pos] = value;
                return old;
            }
            pos = (pos + 1) & mask;
        }
        used[pos] = true;
        keys[pos] = key;
        values[pos] = value;
        size++;
        return null;
    }

    @SuppressWarnings("unchecked")
    private void rehash(int newCapacity) {
        int capacity = arraySize(newCapacity);
        int[] oldKeys = keys;
        Object[] oldValues = values;
        boolean[] oldUsed = used;

        keys = new int[capacity];
        values = new Object[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
        size = 0;

        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                insert(oldKeys[i], (V) oldValues[i]);
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

    private static int mix(int x) {
        int h = x * 0x9E3779B9;
        return h ^ (h >>> 16);
    }

    public static final class EntryIterator<V> {
        private final int[] keys;
        private final Object[] values;
        private final boolean[] used;
        private int index = -1;

        private EntryIterator(int[] keys, Object[] values, boolean[] used) {
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

        public int key() {
            return keys[index];
        }

        @SuppressWarnings("unchecked")
        public V value() {
            return (V) values[index];
        }
    }
}
