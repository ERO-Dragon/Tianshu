package com.rheinmetal.tianshu.api.config;

import java.util.List;

/**
 * One module's own persisted settings.
 *
 * <p>The kernel owns the mechanism and nothing else: it hands a module a namespaced key/value store
 * and never learns which keys that module keeps. Adding a setting therefore changes only the module,
 * which is what keeps the kernel from having to follow every feature release.</p>
 *
 * <p>Values are read from disk on first access and written by {@link #save()}.</p>
 */
public interface ModuleConfigStore {

    String getString(String key, String fallback);

    boolean getBoolean(String key, boolean fallback);

    int getInt(String key, int fallback);

    long getLong(String key, long fallback);

    double getDouble(String key, double fallback);

    List<String> getStringList(String key, List<String> fallback);

    void setString(String key, String value);

    void setBoolean(String key, boolean value);

    void setInt(String key, int value);

    void setLong(String key, long value);

    void setDouble(String key, double value);

    void setStringList(String key, List<String> values);

    /** Persists every value set since the last save. */
    void save();
}
