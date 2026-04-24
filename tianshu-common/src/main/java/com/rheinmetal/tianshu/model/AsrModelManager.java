package com.rheinmetal.tianshu.model;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.rheinmetal.tianshu.constant.VramTier;

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

    private static final String CATALOG_RESOURCE = "/com/rheinmetal/tianshu/constant/asr_catalog.json";
    private static final Gson GSON = new Gson();
    private static List<AsrModelInfo> cachedCatalog = null;

    public static synchronized List<AsrModelInfo> loadCatalog() {
        if (cachedCatalog != null) return cachedCatalog;
        try (InputStream is = AsrModelManager.class.getResourceAsStream(CATALOG_RESOURCE)) {
            if (is == null) return Collections.emptyList();
            try (InputStreamReader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                Type listType = new TypeToken<List<AsrModelInfo>>() {}.getType();
                cachedCatalog = GSON.fromJson(reader, listType);
                return cachedCatalog != null ? cachedCatalog : Collections.emptyList();
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

    public static List<AsrModelInfo> getModelsByTier(VramTier tier) {
        String tierStr = vramTierToRecommendedTier(tier);
        return loadCatalog().stream()
                .filter(m -> tierStr.equalsIgnoreCase(m.getRecommendedTier()))
                .collect(Collectors.toList());
    }

    public static AsrModelInfo getModelByName(String name) {
        if (name == null || name.isBlank()) return null;
        for (AsrModelInfo info : loadCatalog()) {
            if (name.equals(info.name)) return info;
        }
        return null;
    }

    public static AsrModelInfo getModelById(String id) {
        if (id == null || id.isBlank()) return null;
        for (AsrModelInfo info : loadCatalog()) {
            if (id.equals(info.id)) return info;
        }
        return null;
    }

    public static AsrModelInfo getDefaultModel(VramTier tier) {
        String tierStr = vramTierToRecommendedTier(tier);
        List<AsrModelInfo> catalog = loadCatalog();
        AsrModelInfo pinned = catalog.stream()
                .filter(m -> m.pinned && tierStr.equalsIgnoreCase(m.getRecommendedTier()))
                .findFirst()
                .orElse(null);
        if (pinned != null) return pinned;
        return catalog.stream()
                .filter(m -> tierStr.equalsIgnoreCase(m.getRecommendedTier()))
                .findFirst()
                .orElse(null);
    }

    public static boolean isModelDownloaded(AsrModelInfo info, Path baseDir) {
        if (info == null || baseDir == null) return false;
        Path modelDir = baseDir.resolve(info.name);
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
        Path modelDir = baseDir.resolve(info.name);
        return info.getAllRequiredFiles().stream()
                .filter(f -> !Files.isRegularFile(modelDir.resolve(f)))
                .collect(Collectors.toList());
    }

    private static String vramTierToRecommendedTier(VramTier tier) {
        if (tier == null) return AsrModelInfo.TIER_MID;
        return switch (tier) {
            case LIGHT -> AsrModelInfo.TIER_LOW;
            case STANDARD -> AsrModelInfo.TIER_MID;
            case DELUXE -> AsrModelInfo.TIER_HIGH;
            default -> AsrModelInfo.TIER_MID;
        };
    }
}
