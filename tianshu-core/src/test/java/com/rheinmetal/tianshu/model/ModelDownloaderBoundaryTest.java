package com.rheinmetal.tianshu.model;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelDownloaderBoundaryTest {
    private static final Path MODEL_SOURCE = Path.of("src/main/java/com/rheinmetal/tianshu/model");

    @Test
    void huggingFaceFacadeDoesNotOwnRawHttpTransport() throws Exception {
        String source = Files.readString(
                MODEL_SOURCE.resolve("HuggingFaceDownloader.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("HttpURLConnection"));
        assertFalse(source.contains("private void downloadFile("));
        assertFalse(source.contains("new URL("));
    }

    @Test
    void asrFacadeDoesNotOwnRawHttpTransportOrHuggingFaceUrls() throws Exception {
        String source = Files.readString(
                MODEL_SOURCE.resolve("AsrModelDownloader.java"),
                StandardCharsets.UTF_8
        );

        assertFalse(source.contains("HttpURLConnection"));
        assertFalse(source.contains("buildResolveUrl("));
        assertFalse(source.contains("downloadSingleFile("));
        assertFalse(source.contains("downloadSingleFileWithProgress("));
    }

    @Test
    void unusedStandaloneDownloaderIsRemoved() {
        assertFalse(Files.exists(MODEL_SOURCE.resolve("ModelDownloader.java")));
    }

    @Test
    void sharedDownloadDomainDoesNotOwnThreadsOrHostMainThreadDispatch() throws Exception {
        String source;
        try (var files = Files.list(MODEL_SOURCE)) {
            source = files
                    .filter(path -> path.getFileName().toString().contains("Download"))
                    .filter(path -> path.toString().endsWith(".java"))
                    .map(path -> {
                        try {
                            return Files.readString(path, StandardCharsets.UTF_8);
                        } catch (Exception failure) {
                            throw new IllegalStateException(failure);
                        }
                    })
                    .collect(Collectors.joining("\n"));
        }

        assertFalse(source.contains("executeOnMainThread"));
        assertFalse(source.contains("Thread.ofVirtual().start"));
        assertFalse(source.contains("net.minecraft"));
        assertFalse(source.contains("net.neoforged"));
    }
}
