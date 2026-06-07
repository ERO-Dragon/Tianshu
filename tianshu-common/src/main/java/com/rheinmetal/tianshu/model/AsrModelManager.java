package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class AsrModelManager {

    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/asr_model.json";
    private static final Gson GSON = new Gson();
    private static List<AsrModelInfo> cachedCatalog = null;

    public static synchronized List<AsrModelInfo> loadCatalog() {
        if (cachedCatalog != null) return cachedCatalog;
        try (InputStream is = AsrModelManager.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (is == null) return Collections.emptyList();
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<AsrModelInfo>>() {}.getType();
                List<AsrModelInfo> parsed = GSON.fromJson(reader, listType);
                cachedCatalog = parsed == null ? Collections.emptyList() : parsed.stream()
                        .filter(info -> info != null)
                        .collect(Collectors.toList());
                return cachedCatalog;
            }
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    public static synchronized void invalidateCache() {
        cachedCatalog = null;
    }

    public static List<AsrModelInfo> getAllModels() {
        return loadCatalog();
    }

    public static List<AsrModelInfo> getModelsByLang(String lang) {
        return loadCatalog().stream()
                .filter(m -> m.getLang().contains(lang))
                .collect(Collectors.toList());
    }

    public static AsrModelInfo getModelByName(String name) {
        return getModelByLocalKey(name);
    }

    public static AsrModelInfo getModelByLocalKey(String localKey) {
        if (localKey == null || localKey.isBlank()) return null;
        for (AsrModelInfo info : loadCatalog()) {
            if (localKey.equals(info.localKey())) return info;
        }
        return null;
    }

    public static AsrModelInfo getModelByRemoteRepoId(String repoId) {
        if (repoId == null || repoId.isBlank()) return null;
        for (AsrModelInfo info : loadCatalog()) {
            if (repoId.equals(info.remoteRepoId())) return info;
        }
        return null;
    }

    public static boolean isModelDownloaded(AsrModelInfo info, Path baseDir) {
        if (info == null || baseDir == null) return false;
        Path modelDir = baseDir.resolve(info.localKey());
        if (!Files.isDirectory(modelDir)) return false;
        for (String file : info.getAllRequiredFiles()) {
            if (!Files.isRegularFile(modelDir.resolve(file))) {
                return false;
            }
        }
        return true;
    }

    public static List<String> findMissingFiles(AsrModelInfo info, Path baseDir) {
        if (info == null || baseDir == null) return Collections.emptyList();
        Path modelDir = baseDir.resolve(info.localKey());
        return info.getAllRequiredFiles().stream()
                .filter(f -> !Files.isRegularFile(modelDir.resolve(f)))
                .collect(Collectors.toList());
    }
}
