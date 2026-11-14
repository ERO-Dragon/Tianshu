package com.rheinmetal.tianshu.function.echo;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The architecture rule that keeps the cloud backend from leaking into local model handling.
 *
 * <p>Model discovery, download, staging and integrity checking have no cloud concept, and they must not grow
 * one: a cloud backend has no model directory, so any branch for it inside those classes would be a patch
 * over a mismatch rather than a seam. The same rule covers construction — one place builds a backend, so
 * there is one place where native and network resources are acquired.</p>
 */
class TtsCloudBoundaryTest {

    private static final Path SOURCE_ROOT = Path.of("src/main/java/com/rheinmetal/tianshu/function/echo");

    @Test
    void localModelHandlingKnowsNothingAboutCloud() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path file : javaFilesIn(SOURCE_ROOT.resolve("model"), SOURCE_ROOT.resolve("download"))) {
            String content = Files.readString(file, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            if (content.contains("cloud")) {
                offenders.add(file.getFileName().toString());
            }
        }

        assertTrue(offenders.isEmpty(),
                "model/download code must not branch on cloud: " + offenders);
    }

    @Test
    void exactlyOnePlaceConstructsTheCloudBackend() throws IOException {
        List<String> sites = new ArrayList<>();
        for (Path file : javaFilesIn(SOURCE_ROOT)) {
            String content = Files.readString(file, StandardCharsets.UTF_8);
            if (content.contains("new TransportCloudTtsBackend(")) {
                sites.add(file.getFileName().toString());
            }
        }

        assertEquals(List.of("TtsEngineProvider.java"), sites,
                "backend construction belongs in the engine provider only");
    }

    @Test
    void theTokenIsPureComputation() throws IOException {
        String token = Files.readString(
                SOURCE_ROOT.resolve("synthesis/cloud/EdgeTtsToken.java"), StandardCharsets.UTF_8);

        assertTrue(!token.contains("java.net"), "the token must not touch the network");
        assertTrue(!token.contains("Socket"), "the token must not touch a socket");
        assertTrue(!token.contains("HttpClient"), "the token must not own a client");
    }

    @Test
    void theSelectionTypeStaysExhaustive() throws IOException {
        String selection = Files.readString(
                SOURCE_ROOT.resolve("synthesis/TtsBackendSelection.java"), StandardCharsets.UTF_8);

        assertTrue(selection.contains("sealed interface TtsBackendSelection"),
                "the selection must stay sealed so a new backend kind fails to compile at the dispatch");
        assertTrue(selection.contains("LocalTtsBackendSelection"), selection);
        assertTrue(selection.contains("CloudTtsBackendSelection"), selection);
    }

    private static List<Path> javaFilesIn(Path... roots) throws IOException {
        List<Path> files = new ArrayList<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }
}