package com.rheinmetal.tianshu.client.settings.session;

public interface SettingsValue<T> {
    T get();

    void set(T value);

    boolean dirty();

    boolean valid();

    void reset();

    void save();
}
