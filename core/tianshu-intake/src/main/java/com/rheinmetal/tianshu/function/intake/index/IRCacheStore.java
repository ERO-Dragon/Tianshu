package com.rheinmetal.tianshu.function.intake.index;

import com.rheinmetal.tianshu.function.intake.repair.core.IRSnapshot;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * The on-disk IR snapshot: one bounded binary file, written atomically and validated on read.
 *
 * <p>The fingerprint names everything the index contents depend on. Bump {@link #INDEX_SCHEMA_VERSION}
 * whenever tokenizing, pinyin or gram building changes: without it an upgraded mod would happily load a
 * snapshot built by the previous algorithm and silently match nothing.</p>
 */
final class IRCacheStore {
    private static final String CACHE_FILE_NAME = "named-object-ir-cache.bin";
    private static final String TEMPORARY_SUFFIX = ".tmp";

    /**
     * Version of the index-construction algorithm. Not the file format — that is {@code IRSnapshot.VERSION}.
     */
    private static final int INDEX_SCHEMA_VERSION = 1;

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
            updateDigest(digest, "ir-index-v" + INDEX_SCHEMA_VERSION);
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
        long cacheBytes = Files.size(cacheFile);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(cacheFile)))) {
            IRSnapshot snapshot = IRSnapshot.readFrom(input, cacheBytes);
            return fingerprint.equals(snapshot.fingerprint) ? snapshot : null;
        }
    }

    /**
     * Writes the snapshot through a temporary file and one replace.
     *
     * <p>A crash during the write leaves the previous cache intact instead of a half-written file that the
     * next start would have to treat as corrupt.</p>
     */
    void save(IRSnapshot snapshot) throws IOException {
        if (snapshot == null) {
            return;
        }
        Path directory = cacheDirectory();
        Files.createDirectories(directory);
        Path target = directory.resolve(CACHE_FILE_NAME);
        Path temporary = directory.resolve(CACHE_FILE_NAME + TEMPORARY_SUFFIX);
        try {
            try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(
                    Files.newOutputStream(
                            temporary,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING,
                            StandardOpenOption.WRITE)))) {
                snapshot.writeTo(output);
            }
            moveIntoPlace(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Drops a cache file this build could not use; a failed delete is retried next start. */
    void deleteQuietly() {
        try {
            Files.deleteIfExists(cacheFilePath());
        } catch (IOException ignored) {
            // The cache is optional; failing to remove it only costs one more read next time.
        }
    }

    private static void moveIntoPlace(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
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