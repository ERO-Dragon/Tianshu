package com.rheinmetal.tianshu.client.ir;

import com.mojang.logging.LogUtils;
import com.rheinmetal.tianshu.ir.IRCommandService;
import com.rheinmetal.tianshu.ir.IRParseResult;
import com.rheinmetal.tianshu.ir.IRSnapshot;
import com.rheinmetal.tianshu.ir.ParseUnit;
import org.slf4j.Logger;

import java.io.IOException;
import java.util.List;
import java.util.Map;

public final class ClientItemCommandManager {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final IRCommandService IR_SERVICE = new IRCommandService();
    private static final ClientItemDictionaryBuilder DICTIONARY_BUILDER = new ClientItemDictionaryBuilder();
    private static final ClientItemContextCollector CONTEXT_COLLECTOR = new ClientItemContextCollector();
    private static final IRCacheStore CACHE_STORE = new IRCacheStore();

    private static volatile String lastBuildReason = "uninitialized";

    private ClientItemCommandManager() {
    }

    public static void rebuildIndex(String reason) {
        try {
            // 【关键修复】类型对齐为 Map<String, List<String>>
            Map<String, List<String>> dictionary = DICTIONARY_BUILDER.build();
            if (dictionary.isEmpty()) {
                LOGGER.warn("IR 物品字典为空，跳过建表，reason={}", reason);
                IR_SERVICE.clear();
                return;
            }
            
            String fingerprint = CACHE_STORE.buildFingerprint(dictionary);
            IRSnapshot cachedSnapshot = tryLoadSnapshot(fingerprint, reason);
            
            if (cachedSnapshot != null) {
                IR_SERVICE.restore(cachedSnapshot);
                lastBuildReason = reason + " [cache]";
                LOGGER.info("IR 索引缓存加载完成，items={}, reason={}, file={}", IR_SERVICE.getIndexedItemCount(), reason, CACHE_STORE.cacheFilePath());
                return;
            }
            
            IR_SERVICE.rebuild(dictionary);
            trySaveSnapshot(fingerprint, reason);
            lastBuildReason = reason;
            LOGGER.info("IR 索引构建完成，items={}, reason={}, file={}", IR_SERVICE.getIndexedItemCount(), reason, CACHE_STORE.cacheFilePath());
        } catch (Throwable throwable) {
            LOGGER.error("IR 索引构建失败，reason={}", reason, throwable);
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

    public static int getIndexedItemCount() {
        return IR_SERVICE.getIndexedItemCount();
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
                LOGGER.info("IR 缓存未命中，准备重建，reason={}, file={}", reason, CACHE_STORE.cacheFilePath());
            }
            return snapshot;
        } catch (IOException exception) {
            LOGGER.warn("IR 缓存读取失败，改为重建，reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
            return null;
        }
    }

    private static void trySaveSnapshot(String fingerprint, String reason) {
        try {
            IRSnapshot snapshot = IR_SERVICE.snapshot(fingerprint);
            CACHE_STORE.save(snapshot);
        } catch (IOException exception) {
            LOGGER.warn("IR 缓存写入失败，reason={}, file={}", reason, CACHE_STORE.cacheFilePath(), exception);
        }
    }
}
