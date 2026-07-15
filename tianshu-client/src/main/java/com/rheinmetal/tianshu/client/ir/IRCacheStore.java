package com.rheinmetal.tianshu.client.ir;

import com.rheinmetal.tianshu.function.ir.core.IRSnapshot;

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
import java.util.Objects;
import java.util.function.Supplier;

final class IRCacheStore {
    private static final String CACHE_FILE_NAME = "named-object-ir-cache.bin";

    private final Path cacheDirectory;
    private final Supplier<String> languageCodeSupplier;

    IRCacheStore(Path cacheDirectory, Supplier<String> languageCodeSupplier) {
        this.cacheDirectory = Objects.requireNonNull(cacheDirectory, "cacheDirectory").toAbsolutePath().normalize();
        this.languageCodeSupplier = Objects.requireNonNull(languageCodeSupplier, "languageCodeSupplier");
    }

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
                // Alias changes must invalidate the cached index.
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
        return cacheDirectory;
    }

    private String currentLanguageCode() {
        String value = languageCodeSupplier.get();
        return value == null || value.isBlank() ? "unknown" : value.trim();
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
