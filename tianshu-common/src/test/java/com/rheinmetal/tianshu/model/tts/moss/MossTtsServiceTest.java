package com.rheinmetal.tianshu.model.tts.moss;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTtsServiceTest {
    @Test
    void fillsManifestCompatibleGenerationDefaults() {
        JsonObject defaults = MossTtsService.normalizeGenerationDefaults(new JsonObject());

        assertEquals(375, defaults.get("max_new_frames").getAsInt());
        assertTrue(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossTtsService.SAMPLE_MODE_FIXED, defaults.get("sample_mode").getAsString());
        assertEquals(50, defaults.get("text_top_k").getAsInt());
        assertEquals(0.8f, defaults.get("audio_temperature").getAsFloat());
        assertEquals(25, defaults.get("audio_top_k").getAsInt());
        assertEquals(0.95f, defaults.get("audio_top_p").getAsFloat());
        assertEquals(1.2f, defaults.get("audio_repetition_penalty").getAsFloat());
    }

    @Test
    void keepsExplicitFixedSamplingWhenSamplingIsEnabled() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("max_new_frames", 120);
        defaults.addProperty("do_sample", true);
        defaults.addProperty("sample_mode", "fixed");

        MossTtsService.normalizeGenerationDefaults(defaults);

        assertEquals(120, defaults.get("max_new_frames").getAsInt());
        assertTrue(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossTtsService.SAMPLE_MODE_FIXED, defaults.get("sample_mode").getAsString());
    }

    @Test
    void normalizesDisabledSamplingToGreedy() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("do_sample", false);
        defaults.addProperty("sample_mode", "fixed");

        MossTtsService.normalizeGenerationDefaults(defaults);

        assertFalse(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossTtsService.SAMPLE_MODE_GREEDY, defaults.get("sample_mode").getAsString());
    }

    @Test
    void greedySampleModeDisablesSamplingLikePythonRuntime() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("do_sample", true);
        defaults.addProperty("sample_mode", "greedy");

        MossTtsService.normalizeGenerationDefaults(defaults);

        assertFalse(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossTtsService.SAMPLE_MODE_GREEDY, defaults.get("sample_mode").getAsString());
    }
}
