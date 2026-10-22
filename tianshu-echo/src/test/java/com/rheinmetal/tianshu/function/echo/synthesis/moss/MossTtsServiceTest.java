package com.rheinmetal.tianshu.function.echo.synthesis.moss;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossTtsServiceTest {
    @Test
    void fillsManifestCompatibleGenerationDefaults() {
        JsonObject defaults = MossFrameGenerator.normalizeGenerationDefaults(new JsonObject());

        assertEquals(375, defaults.get("max_new_frames").getAsInt());
        assertTrue(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossFrameGenerator.SAMPLE_MODE_FIXED, defaults.get("sample_mode").getAsString());
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

        MossFrameGenerator.normalizeGenerationDefaults(defaults);

        assertEquals(120, defaults.get("max_new_frames").getAsInt());
        assertTrue(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossFrameGenerator.SAMPLE_MODE_FIXED, defaults.get("sample_mode").getAsString());
    }

    @Test
    void normalizesDisabledSamplingToGreedy() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("do_sample", false);
        defaults.addProperty("sample_mode", "fixed");

        MossFrameGenerator.normalizeGenerationDefaults(defaults);

        assertFalse(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossFrameGenerator.SAMPLE_MODE_GREEDY, defaults.get("sample_mode").getAsString());
    }

    @Test
    void greedySampleModeDisablesSamplingLikePythonRuntime() {
        JsonObject defaults = new JsonObject();
        defaults.addProperty("do_sample", true);
        defaults.addProperty("sample_mode", "greedy");

        MossFrameGenerator.normalizeGenerationDefaults(defaults);

        assertFalse(defaults.get("do_sample").getAsBoolean());
        assertEquals(MossFrameGenerator.SAMPLE_MODE_GREEDY, defaults.get("sample_mode").getAsString());
    }

    @Test
    void streamingMetadataReportsPcmDurationAndInterChunkInterval() {
        assertEquals(100L, MossTtsService.pcmDurationMillis(
                new float[][]{new float[2_400], new float[2_400]},
                24_000
        ));
        assertEquals(0L, MossTtsService.pcmDurationMillis(new float[][]{new float[0]}, 24_000));

        MossTtsService.StreamingChunkMetadata metadata = new MossTtsService.StreamingChunkMetadata(100L, 250L, 75L);
        assertEquals(100L, metadata.pcmDurationMillis());
        assertEquals(250L, metadata.generationElapsedMillis());
        assertEquals(75L, metadata.interChunkIntervalMillis());

        MossTtsService.StreamingChunkMetadata sanitized = new MossTtsService.StreamingChunkMetadata(-1L, -2L, -3L);
        assertEquals(0L, sanitized.pcmDurationMillis());
        assertEquals(0L, sanitized.generationElapsedMillis());
        assertEquals(0L, sanitized.interChunkIntervalMillis());
    }
}
