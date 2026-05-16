package com.rheinmetal.tianshu.client.gui.settings.session;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public final class MutableSettingsValue<T> implements SettingsValue<T> {
    private final Supplier<T> source;
    private final Consumer<T> sink;
    private final Predicate<T> validator;
    private T original;
    private T value;

    public MutableSettingsValue(Supplier<T> source, Consumer<T> sink) {
        this(source, sink, ignored -> true);
    }

    public MutableSettingsValue(Supplier<T> source, Consumer<T> sink, Predicate<T> validator) {
        this.source = source;
        this.sink = sink;
        this.validator = validator == null ? ignored -> true : validator;
        this.original = source == null ? null : source.get();
        this.value = original;
    }

    public T get() {
        return value;
    }

    public void set(T value) {
        this.value = value;
    }

    public boolean dirty() {
        return !Objects.equals(original, value);
    }

    public boolean valid() {
        return validator.test(value);
    }

    public void reset() {
        this.value = original;
    }

    public void save() {
        if (sink != null) {
            sink.accept(value);
        }
        this.original = value;
    }
}
