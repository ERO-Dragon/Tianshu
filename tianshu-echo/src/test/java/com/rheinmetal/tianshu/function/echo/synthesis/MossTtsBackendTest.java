package com.rheinmetal.tianshu.function.echo.synthesis;

import com.rheinmetal.tianshu.function.echo.model.TtsModelInfo;
import com.rheinmetal.tianshu.testkit.FakeGameEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A backend must not make a model look installed on disk while failing because it is not: the model
 * service owns installation, and an empty directory it did not create would split "directory exists"
 * from "model installed".
 */
class MossTtsBackendTest {

    @TempDir
    Path tempDir;

    @Test
    void loadingAModelThatWasNeverDownloadedDoesNotCreateItsDirectory() {
        Path modelDir = tempDir.resolve("tts").resolve("model").resolve("moss-never-downloaded");
        FakeGameEnvironment env = new FakeGameEnvironment();
        // The codec lane is never reached: the model check fails during init, before any synthesis.
        MossTtsBackend backend = new MossTtsBackend(env, work -> {
            throw new UnsupportedOperationException("no codec lane in this test");
        });

        TtsResolvedModel model = new TtsResolvedModel(modelDir, mossInfo(), TtsBackendType.MOSS);

        assertFalse(backend.initialize(model), "an uninstalled model cannot initialize");
        assertFalse(Files.exists(modelDir), "a failed load must not leave an empty model directory behind");
        assertFalse(backend.isInitialized());
        assertTrue(env.errors.contains("MOSS-TTS backend initialization failed"),
                "the failure has to be reported instead of being treated as a successful no-op");

        // Leftover state must not turn the second attempt into a success either.
        assertFalse(backend.initialize(model), "the retry has to report the same failure");
        assertFalse(Files.exists(modelDir));
        backend.shutdown();
    }

    private static TtsModelInfo mossInfo() {
        TtsModelInfo info = new TtsModelInfo();
        info.name = "moss-never-downloaded";
        info.engine = TtsModelInfo.ENGINE_MOSS;
        return info;
    }
}
