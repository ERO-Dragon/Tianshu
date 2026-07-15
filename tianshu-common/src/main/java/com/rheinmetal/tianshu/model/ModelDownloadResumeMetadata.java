package com.rheinmetal.tianshu.model;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.Reader;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Optional;

record ModelDownloadResumeMetadata(
        URI source,
        ValidatorKind validatorKind,
        String validator,
        long totalLength
) {
    private static final int SCHEMA_VERSION = 1;
    private static final com.google.gson.Gson GSON = new com.google.gson.GsonBuilder()
            .disableHtmlEscaping()
            .create();

    ModelDownloadResumeMetadata {
        if (source == null || validatorKind == null || validator == null || validator.isBlank() || totalLength < 1L) {
            throw new IllegalArgumentException("invalid model download resume metadata");
        }
        validator = validator.trim();
    }

    static Optional<ModelDownloadResumeMetadata> fromResponse(URI source, HttpURLConnection connection) {
        if (source == null || connection == null || !supportsByteRanges(connection)) {
            return Optional.empty();
        }
        long totalLength = connection.getContentLengthLong();
        if (totalLength < 1L) {
            return Optional.empty();
        }
        String etag = clean(connection.getHeaderField("ETag"));
        if (!etag.isEmpty() && !etag.regionMatches(true, 0, "W/", 0, 2)) {
            return Optional.of(new ModelDownloadResumeMetadata(source, ValidatorKind.ETAG, etag, totalLength));
        }
        String lastModified = clean(connection.getHeaderField("Last-Modified"));
        if (!lastModified.isEmpty()) {
            return Optional.of(new ModelDownloadResumeMetadata(source, ValidatorKind.LAST_MODIFIED, lastModified, totalLength));
        }
        return Optional.empty();
    }

    static Optional<ModelDownloadResumeMetadata> read(Path path) {
        if (path == null || !Files.isRegularFile(path)) {
            return Optional.empty();
        }
        try (Reader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            JsonObject json = JsonParser.parseReader(reader).getAsJsonObject();
            if (json.get("schemaVersion").getAsInt() != SCHEMA_VERSION) {
                return Optional.empty();
            }
            return Optional.of(new ModelDownloadResumeMetadata(
                    URI.create(json.get("source").getAsString()),
                    ValidatorKind.valueOf(json.get("validatorKind").getAsString()),
                    json.get("validator").getAsString(),
                    json.get("totalLength").getAsLong()
            ));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    void write(Path path) throws java.io.IOException {
        Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
        JsonObject json = new JsonObject();
        json.addProperty("schemaVersion", SCHEMA_VERSION);
        json.addProperty("source", source.toASCIIString());
        json.addProperty("validatorKind", validatorKind.name());
        json.addProperty("validator", validator);
        json.addProperty("totalLength", totalLength);
        try {
            try (Writer writer = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING,
                    java.nio.file.StandardOpenOption.WRITE
            )) {
                GSON.toJson(json, writer);
            }
            move(temporary, path);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    boolean matchesResponse(HttpURLConnection connection) {
        String header = validatorKind == ValidatorKind.ETAG
                ? connection.getHeaderField("ETag")
                : connection.getHeaderField("Last-Modified");
        return validator.equals(clean(header));
    }

    private static boolean supportsByteRanges(HttpURLConnection connection) {
        String value = clean(connection.getHeaderField("Accept-Ranges")).toLowerCase(Locale.ROOT);
        for (String token : value.split(",")) {
            if ("bytes".equals(token.trim())) {
                return true;
            }
        }
        return false;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }

    private static void move(Path source, Path target) throws java.io.IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    enum ValidatorKind {
        ETAG,
        LAST_MODIFIED
    }
}
