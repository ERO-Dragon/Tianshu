package com.rheinmetal.tianshu.function.ir.core.collection;

public final class IntOpenHashSet {
    private static final float LOAD_FACTOR = 0.6f;

    private int[] keys;
    private boolean[] used;
    private int mask;
    private int size;
    private int threshold;

    public IntOpenHashSet() {
        this(16);
    }

    public IntOpenHashSet(int expected) {
        int capacity = arraySize(expected);
        keys = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
    }

    public boolean add(int key) {
        if (size >= threshold) {
            rehash(keys.length << 1);
        }
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                return false;
            }
            pos = (pos + 1) & mask;
        }
        used[pos] = true;
        keys[pos] = key;
        size++;
        return true;
    }

    public boolean contains(int key) {
        int pos = mix(key) & mask;
        while (used[pos]) {
            if (keys[pos] == key) {
                return true;
            }
            pos = (pos + 1) & mask;
        }
        return false;
    }

    public int size() {
        return size;
    }

    private void rehash(int newCapacity) {
        int capacity = arraySize(newCapacity);
        int[] oldKeys = keys;
        boolean[] oldUsed = used;

        keys = new int[capacity];
        used = new boolean[capacity];
        mask = capacity - 1;
        threshold = Math.max(1, (int) (capacity * LOAD_FACTOR));
        size = 0;

        for (int i = 0; i < oldUsed.length; i++) {
            if (oldUsed[i]) {
                add(oldKeys[i]);
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
}
