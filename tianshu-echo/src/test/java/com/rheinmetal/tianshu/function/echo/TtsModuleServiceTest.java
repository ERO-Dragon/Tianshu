package com.rheinmetal.tianshu.function.echo;

import com.rheinmetal.tianshu.function.echo.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.echo.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.echo.runtime.TtsRuntimeSnapshot;
import com.rheinmetal.tianshu.function.echo.runtime.TtsVoiceProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsModuleServiceTest {
    @Test
    void previewModelRejectsWhenRuntimeIsNotBound() {
        TtsModuleService service = new TtsModuleService();

        TtsOperationResult result = service.previewModel("model", "text", TtsVoiceProfile.defaults(), null, null);

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.RUNTIME_NOT_RUNNING, result.failure().code());
    }

    @Test
    void snapshotIsUnboundWhenRuntimeIsNotBound() {
        TtsModuleService service = new TtsModuleService();

        TtsRuntimeSnapshot snapshot = service.snapshot();

        assertFalse(snapshot.bound());
        assertFalse(snapshot.ready());
        assertEquals(TtsFailureCode.UNKNOWN, snapshot.lastFailureCode());
    }

    @Test
    void stopPreviewRejectsWhenRuntimeIsNotBound() {
        TtsModuleService service = new TtsModuleService();

        TtsControlResult result = service.stopPreview("stop preview");

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.RUNTIME_NOT_RUNNING, result.failure().code());
    }

    @Test
    void reloadModelRejectsWhenRuntimeIsNotBound() {
        TtsModuleService service = new TtsModuleService();

        TtsControlResult result = service.reloadModel();

        assertFalse(result.accepted());
        assertEquals(TtsFailureCode.RUNTIME_NOT_RUNNING, result.failure().code());
    }
}
