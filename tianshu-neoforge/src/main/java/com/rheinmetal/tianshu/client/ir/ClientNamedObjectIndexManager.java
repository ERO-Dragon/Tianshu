package com.rheinmetal.tianshu.client.ir;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.function.ir.core.IRCommandService;
import com.rheinmetal.tianshu.function.ir.core.IRParseResult;
import com.rheinmetal.tianshu.function.ir.core.IRSnapshot;
import com.rheinmetal.tianshu.function.ir.core.ParseUnit;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class ClientNamedObjectIndexManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final IRCommandService IR_SERVICE = new IRCommandService();
    private static final ClientItemDictionaryBuilder ITEM_DICTIONARY_BUILDER = new ClientItemDictionaryBuilder();
    private static final ClientEntityDictionaryBuilder ENTITY_DICTIONARY_BUILDER = new ClientEntityDictionaryBuilder();
    private static final ClientItemContextCollector CONTEXT_COLLECTOR = new ClientItemContextCollector();
    private static final IRCacheStore CACHE_STORE = new IRCacheStore();

    private static volatile String lastBuildReason = "uninitialized";

    private ClientNamedObjectIndexManager() {
    }

    public static void rebuildIndex(String reason) {
        try {
            Map<String, List<String>> dictionary = buildDictionary();
            if (dictionary.isEmpty()) {
                LOGGER.warn("IR named object dictionary is empty, skip index rebuild, reason={}", reason);
                IR_SERVICE.clear();
                return;
            }

            String fingerprint = CACHE_STORE.buildFingerprint(dictionary);
            IRSnapshot cachedSnapshot = tryLoadSnapshot(fingerprint, reason);
            if (cachedSnapshot != null) {
                IR_SERVICE.restore(cachedSnapshot);
                lastBuildReason = reason + " [cache]";
                LOGGER.info("IR named object index loaded from cache, objects={}, reason={}, file={}", getIndexedObjectCount(), reason, CACHE_STORE.cacheFilePath());
                return;
            }

            IR_SERVICE.rebuild(dictionary);
            trySaveSnapshot(fingerprint, reason);
            lastBuildReason = reason;
            LOGGER.info("IR named object index rebuilt, objects={}, reason={}, file={}", getIndexedObjectCount(), reason, CACHE_STORE.cacheFilePath());
        } catch (Throwable throwable) {
            LOGGER.error("IR named object index rebuild failed, reason={}", reason, throwable);
        }
    }

    public static void ensureIndex(String reason) {
        if (!IR_SERVICE.isReady()) {
            rebuildIndex(reason);
        }
    }

    public static boolean isReady() {
        return IR_SERVICE.isReady();
    }

    public static int getIndexedObjectCount() {
        return IR_SERVICE.getIndexedObjectCount();
    }

    public static String resolveDisplayName(String realItemId) {
        ensureIndex("display name resolve");
        return IR_SERVICE.resolveDisplayName(realItemId);
    }

    public static String resolveEntityDisplayName(String entityTypeId) {
        ensureIndex("entity display name resolve");
        return IR_SERVICE.resolveEntityDisplayName(entityTypeId);
    }

    public static String getLastBuildReason() {
        return lastBuildReason;
    }

    public static IRParseResult parsePlayerCommand(String rawText, boolean isFastIR) {
        ensureIndex("parse fallback");
        return IR_SERVICE.parse(rawText, CONTEXT_COLLECTOR, isFastIR);
    }

    public static String formatPreview(IRParseResult parseResult) {
        if (parseResult == null || !parseResult.hasUnits()) {
            return "";
        }
        List<ParseUnit> units = parseResult.getUnits();
        StringBuilder builder = new StringBuilder(units.size() * 24);
        for (int i = 0; i < units.size(); i++) {
            ParseUnit unit = units.get(i);
            if (i > 0) {
                builder.append(" | ");
            }
            builder.append(unit.intent)
                    .append(':')
                    .append(unit.targetRealItemId);
            if (unit.isNegated) {
                builder.append(" [neg]");
            }
        }
        return builder.toString();
    }

    private static IRSnapshot tryLoadSnapshot(String fingerprint, String reason) {
        try {
            IRSnapshot snapshot = CACHE_STORE.loadIfMatches(fingerprint);
            if (snapshot == null) {
                LOGGER.info("IR index cache miss, rebuild will continue, reason={}, file={}", reason, CACHE_STORE.cacheFilePath());
            }
            return snapshot;
        } catch (IOException exception) {
            LOGGER.warn("IR index cache read failed, rebuild will continue, reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
            return null;
        }
    }

    private static void trySaveSnapshot(String fingerprint, String reason) {
        try {
            IRSnapshot snapshot = IR_SERVICE.snapshot(fingerprint);
            CACHE_STORE.save(snapshot);
        } catch (IOException exception) {
            LOGGER.warn("IR index cache write failed, reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
        }
    }

    private static Map<String, List<String>> buildDictionary() {
        LinkedHashMap<String, List<String>> dictionary = new LinkedHashMap<>();
        dictionary.putAll(ITEM_DICTIONARY_BUILDER.build());
        dictionary.putAll(ENTITY_DICTIONARY_BUILDER.build());
        return dictionary;
    }
}
