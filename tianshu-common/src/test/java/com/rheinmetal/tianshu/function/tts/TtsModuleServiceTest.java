package com.rheinmetal.tianshu.function.tts;

import com.rheinmetal.tianshu.function.tts.runtime.TtsBackendSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;
import com.rheinmetal.tianshu.function.tts.runtime.TtsModelSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsOperationResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRuntimeSnapshot;
import com.rheinmetal.tianshu.function.tts.runtime.TtsVoiceProfile;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class TtsModuleServiceTest {
    @Test
    void previewRejectsEmptyTextWithStructuredFailure() {
        TtsModuleService service = new TtsModuleService();

        TtsOperationResult result = service.preview("   ", TtsVoiceProfile.defaults(), null, null);

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
    void unboundModelAndBackendSnapshotsUseSafeDefaults() {
        TtsModuleService service = new TtsModuleService();

        TtsModelSnapshot modelSnapshot = service.modelSnapshot();
        TtsBackendSnapshot backendSnapshot = service.backendSnapshot();

        assertFalse(modelSnapshot.configured());
        assertFalse(backendSnapshot.resolved());
        assertFalse(backendSnapshot.initialized());
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
