package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MossAudioCodecBoundaryTest {
    private static final Path SERVICE_SOURCE = Path.of(
            "src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/moss/MossTtsService.java"
    );
    private static final Path CODEC_SOURCE = Path.of(
            "src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/moss/MossAudioCodec.java"
    );

    @Test
    void serviceDelegatesPromptEncodingAndAudioDecodingToCodec() throws Exception {
        Field[] fields = MossTtsService.class.getDeclaredFields();
        String source = Files.readString(SERVICE_SOURCE, StandardCharsets.UTF_8);

        assertTrue(Arrays.stream(fields).anyMatch(field -> field.getType() == MossAudioCodec.class));
        assertFalse(source.contains("StreamingCodecDecoder"));
        assertFalse(source.contains("createStreamingDecodeState("));
        assertFalse(source.contains("codec_decode_step"));
    }

    @Test
    void streamingDecoderAcceptsThePipelineChosenFrameBatch() throws Exception {
        String source = Files.readString(CODEC_SOURCE, StandardCharsets.UTF_8);

        assertTrue(Arrays.stream(MossAudioCodec.StreamingDecoder.class.getDeclaredMethods())
                .anyMatch(method -> method.getName().equals("decodeFrames")
                        && Arrays.equals(method.getParameterTypes(), new Class<?>[]{List.class})));
        assertFalse(source.contains("STREAMING_DECODE_CHUNK_FRAMES"));
    }

    @Test
    void channelChunksMergeInOrderWithoutDroppingFirstSamples() {
        float[][] merged = MossAudioCodec.mergeChannelChunks(List.of(
                new float[][]{{1.0f, 2.0f}, {10.0f, 20.0f}},
                new float[][]{{3.0f}, {30.0f}}
        ));

        assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f}, merged[0]);
        assertArrayEquals(new float[]{10.0f, 20.0f, 30.0f}, merged[1]);
    }
}
