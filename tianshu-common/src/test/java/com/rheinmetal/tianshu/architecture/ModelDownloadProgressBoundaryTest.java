package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.model.ModelDownloadProgress;
import com.rheinmetal.tianshu.model.ModelDownloadStage;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class ModelDownloadProgressBoundaryTest {
    @Test
    void progressIsStructuredAndBounded() {
        ModelDownloadProgress progress = new ModelDownloadProgress(
                ModelDownloadStage.DOWNLOADING,
                140,
                -1L,
                -2L,
                "hf.file"
        );

        assertEquals(ModelDownloadStage.DOWNLOADING, progress.stage());
        assertEquals(100, progress.percent());
        assertEquals(0L, progress.downloadedBytes());
        assertEquals(0L, progress.totalBytes());
        assertEquals("hf.file", progress.detailCode());
    }

    @Test
    void commonProductionDoesNotExposeLocalizedProgressLabels() throws Exception {
        List<Path> roots = List.of(
                Path.of("tianshu-common/src/main/java/com/rheinmetal/tianshu/model"),
                Path.of("tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr"),
                Path.of("tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm"),
                Path.of("tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts")
        );
        for (Path relativeRoot : roots) {
            Path root = resolveFromWorkspace(relativeRoot);
            try (var files = Files.walk(root)) {
                for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file, StandardCharsets.UTF_8);
                    assertFalse(source.contains("onProgress(String label, int percent)"), file.toString());
                }
            }
        }
    }

    private static Path resolveFromWorkspace(Path relativePath) {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 5 && current != null; depth++) {
            Path candidate = current.resolve(relativePath).normalize();
            if (Files.exists(candidate)) {
                return candidate;
            }
            current = current.getParent();
        }
        return relativePath;
    }
}
