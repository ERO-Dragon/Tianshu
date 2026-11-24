package com.rheinmetal.tianshu.dubhe.agent.skill.book;

import com.rheinmetal.tianshu.api.assembly.ModResources;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

/**
 * An in-memory stand-in for a set of installed mods.
 *
 * <p>Files are addressed the way the real port addresses them — {@code (modId, path)} — so a test reads like the
 * layout it is checking, and directory listing is derived from the file names instead of being declared twice.</p>
 */
final class FakeModResources implements ModResources {

    private final Map<String, String> files;

    private FakeModResources(Map<String, String> files) {
        this.files = files;
    }

    @Override
    public List<String> children(String modId, String directory) {
        String prefix = modId + "|" + directory + "/";
        TreeSet<String> names = new TreeSet<>();
        for (String key : files.keySet()) {
            if (!key.startsWith(prefix)) {
                continue;
            }
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            names.add(slash < 0 ? rest : rest.substring(0, slash));
        }
        return List.copyOf(names);
    }

    @Override
    public Optional<String> read(String modId, String path) {
        return Optional.ofNullable(files.get(modId + "|" + path));
    }

    static Builder builder() {
        return new Builder();
    }

    static final class Builder {

        private final Map<String, String> files = new LinkedHashMap<>();

        Builder put(String modId, String path, String content) {
            files.put(modId + "|" + path, content);
            return this;
        }

        FakeModResources build() {
            return new FakeModResources(new LinkedHashMap<>(files));
        }
    }
}