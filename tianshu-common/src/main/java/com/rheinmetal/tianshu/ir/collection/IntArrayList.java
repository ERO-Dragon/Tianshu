package com.rheinmetal.tianshu.ir.collection;

import java.util.Arrays;

public final class IntArrayList {
    private int[] elements;
    private int size;

    public IntArrayList() {
        this(16);
    }

    public IntArrayList(int initialCapacity) {
        this.elements = new int[Math.max(1, initialCapacity)];
    }

    public void add(int value) {
        ensureCapacity(size + 1);
        elements[size++] = value;
    }

    public int getInt(int index) {
        if (index < 0 || index >= size) {
            throw new IndexOutOfBoundsException("index=" + index + ", size=" + size);
        }
        return elements[index];
    }

    public int size() {
        return size;
    }

    public int[] elements() {
        return elements;
    }

    public int[] toIntArray() {
        return Arrays.copyOf(elements, size);
    }

    public void clear() {
        size = 0;
    }

    private void ensureCapacity(int capacity) {
        if (capacity <= elements.length) {
            return;
        }
        int newCapacity = elements.length + (elements.length >> 1) + 1;
        if (newCapacity < capacity) {
            newCapacity = capacity;
        }
        elements = Arrays.copyOf(elements, newCapacity);
    }
}
