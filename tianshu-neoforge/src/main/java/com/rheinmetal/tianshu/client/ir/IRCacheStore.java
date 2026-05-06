package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.core.IRSnapshot;
import net.minecraft.client.Minecraft;
import net.minecraft.locale.Language;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

final class IRCacheStore {
    private static final String CACHE_FILE_NAME = "item-ir-cache.bin";
    private static final String CACHE_DIR_NAME = "TianshuAIAssistant";
    private static final String CACHE_SUB_DIR_NAME = "cache";

    Path cacheFilePath() {
        return cacheDirectory().resolve(CACHE_FILE_NAME);
    }

    String buildFingerprint(Map<String, List<String>> dictionary) {
        String languageCode = currentLanguageCode();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, languageCode);
            
            for (Map.Entry<String, List<String>> entry : dictionary.entrySet()) {
                updateDigest(digest, entry.getKey());
                // 【修复点 2】把该物品的所有别名都纳入指纹计算
                // 这样只要别名列表有任何增减变化，指纹就会改变，触发索引重建
                List<String> aliases = entry.getValue();
                if (aliases != null) {
                    for (String alias : aliases) {
                        updateDigest(digest, alias);
                    }
                }
            }
            
            return languageCode + "-" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    IRSnapshot loadIfMatches(String fingerprint) throws IOException {
        Path cacheFile = cacheFilePath();
        if (!Files.isRegularFile(cacheFile)) {
            return null;
        }
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            IRSnapshot snapshot = IRSnapshot.readFrom(input);
            return fingerprint.equals(snapshot.fingerprint) ? snapshot : null;
        }
    }

    void save(IRSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        Path cacheDirectory = cacheDirectory();
        Files.createDirectories(cacheDirectory);
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(cacheDirectory.resolve(CACHE_FILE_NAME))))) {
            snapshot.writeTo(output);
        }
    }

    private Path cacheDirectory() {
        Path gameDir = Minecraft.getInstance().gameDirectory.toPath();
        return gameDir.resolve("config").resolve(CACHE_DIR_NAME).resolve(CACHE_SUB_DIR_NAME);
    }

    private String currentLanguageCode() {
        return Language.getInstance().getOrDefault("language.code");
    }

    private void updateDigest(MessageDigest digest, String value) {
        String safeValue = value == null ? "" : value;
        for (int i = 0; i < safeValue.length(); i++) {
            char c = safeValue.charAt(i);
            digest.update((byte) (c >>> 8));
            digest.update((byte) c);
        }
        digest.update((byte) 0);
    }
}
