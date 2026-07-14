package com.rheinmetal.tianshu.function.asr;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;

class AsrRuntimeFailureBoundaryTest {
    private static final List<Path> FAILURE_BOUNDARIES = List.of(
            Path.of("src/main/java/com/rheinmetal/tianshu/function/asr/AsrModule.java"),
            Path.of("src/main/java/com/rheinmetal/tianshu/function/asr/audio/AudioCaptureService.java"),
            Path.of("src/main/java/com/rheinmetal/tianshu/function/asr/engine/AsrEngine.java"),
            Path.of("src/main/java/com/rheinmetal/tianshu/function/asr/engine/AsrEngineBootstrap.java")
    );

    @Test
    void asrRuntimeBoundariesDoNotCatchEveryJvmThrowable() throws Exception {
        for (Path sourcePath : FAILURE_BOUNDARIES) {
            String source = Files.readString(sourcePath, StandardCharsets.UTF_8);
            assertFalse(
                    source.contains("catch (Throwable"),
                    () -> sourcePath + " must distinguish recoverable runtime/native failures from fatal JVM errors"
            );
        }
    }
}
