package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sentencepiece.SentencePieceProcessor;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Verifies that the packaged tokenizer produces the model manifest's reference token sequence. */
class MossTokenizerParitySmokeTest {
    @Test
    void encodesManifestChineseReferenceExactly() throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("TIANSHU_MOSS_SMOKE")),
                "Set TIANSHU_MOSS_SMOKE=true to run the real MOSS tokenizer parity smoke test");

        Path modelDir = resolveExistingPath(
                Path.of("libs", "MOSS-TTS-Nano-main", "model"),
                Path.of("..", "libs", "MOSS-TTS-Nano-main", "model")
        );
        JsonObject manifest = JsonParser.parseString(
                Files.readString(modelDir.resolve("browser_poc_manifest.json"))
        ).getAsJsonObject();
        JsonObject sample = manifest.getAsJsonArray("text_samples").get(0).getAsJsonObject();
        int[] expected = tokenIds(sample.getAsJsonArray("text_token_ids"));

        SentencePieceProcessor tokenizer = new SentencePieceProcessor(modelDir.resolve("tokenizer.model"));
        List<Integer> actualIds = tokenizer.encode(sample.get("text").getAsString());
        int[] actual = actualIds.stream().mapToInt(Integer::intValue).toArray();

        assertArrayEquals(expected, actual);
    }

    private static int[] tokenIds(JsonArray ids) {
        int[] result = new int[ids.size()];
        for (int index = 0; index < ids.size(); index++) {
            result[index] = ids.get(index).getAsInt();
        }
        return result;
    }

    private static Path resolveExistingPath(Path first, Path second) {
        return Files.exists(first) ? first.normalize() : second.normalize();
    }
}
