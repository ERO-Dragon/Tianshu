package com.rheinmetal.tianshu.function.asr;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AsrModelServicePreviewBoundaryTest {
    @Test
    void modelServiceDelegatesPreviewLifecycleWithoutBlockingOrDirectStopIo() throws Exception {
        String code = read("src/main/java/com/rheinmetal/tianshu/function/asr/AsrModelService.java");

        assertTrue(code.contains("AsrPreviewCoordinator previewCoordinator"));
        assertFalse(code.contains("Thread.sleep(5000)"));
        assertFalse(code.contains("catch (Throwable ignored)"));

        String stopPreview = methodBody(code, "public void stopPreview()");
        assertTrue(stopPreview.contains("previewCoordinator.stop()"));
        assertFalse(stopPreview.contains("audioBridge"));
    }

    @Test
    void moduleStopAndDestroyOwnPreviewShutdown() throws Exception {
        String code = read("src/main/java/com/rheinmetal/tianshu/function/asr/AsrModule.java");

        String stop = methodBody(code, "public void stop()");
        String destroy = methodBody(code, "public void destroy()");
        assertTrue(stop.contains("modelService.stopPreview()"));
        assertTrue(destroy.contains("modelService.close()"));
    }

    @Test
    void previewFailuresUseResourceKeysThatTheHostLocalizes() throws Exception {
        String service = read("src/main/java/com/rheinmetal/tianshu/function/asr/AsrModelService.java");
        String gui = read("../tianshu-client/src/main/java/com/rheinmetal/tianshu/client/gui/asr/AsrSettingsRegistrySource.java");

        assertTrue(service.contains("tianshu.gui.asr.failure."));
        assertFalse(service.contains("ASR preview is already running."));
        assertFalse(service.contains("No audio data was captured."));
        assertTrue(gui.contains("UiText.key(message)"));
    }

    private static String read(String path) throws Exception {
        return Files.readString(Path.of(path), StandardCharsets.UTF_8);
    }

    private static String methodBody(String code, String signature) {
        int start = code.indexOf(signature);
        if (start < 0) {
            throw new AssertionError("Missing method: " + signature);
        }
        int openBrace = code.indexOf('{', start);
        int depth = 0;
        for (int index = openBrace; index < code.length(); index++) {
            char value = code.charAt(index);
            if (value == '{') depth++;
            if (value == '}' && --depth == 0) {
                return code.substring(openBrace + 1, index);
            }
        }
        throw new AssertionError("Unclosed method: " + signature);
    }
}
