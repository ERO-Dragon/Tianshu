package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WavWriter {

    private WavWriter() {
    }

    public static void writeWaveFile(Path outputPath, float[][] channels, int sampleRate) throws IOException {
        if (channels == null || channels.length == 0) {
            throw new IllegalArgumentException("channels must not be empty");
        }

        int channelCount = channels.length;
        int sampleCount = channels[0].length;
        for (float[] channel : channels) {
            if (channel.length != sampleCount) {
                throw new IllegalArgumentException("all channels must have the same sample length");
            }
        }

        Path parent = outputPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        int bitsPerSample = 16;
        int blockAlign = channelCount * bitsPerSample / 8;
        int byteRate = sampleRate * blockAlign;
        int dataSize = sampleCount * blockAlign;
        int chunkSize = 36 + dataSize;

        try (OutputStream outputStream = Files.newOutputStream(outputPath)) {
            writeAscii(outputStream, "RIFF");
            writeInt32LE(outputStream, chunkSize);
            writeAscii(outputStream, "WAVE");
            writeAscii(outputStream, "fmt ");
            writeInt32LE(outputStream, 16);
            writeInt16LE(outputStream, (short) 1);
            writeInt16LE(outputStream, (short) channelCount);
            writeInt32LE(outputStream, sampleRate);
            writeInt32LE(outputStream, byteRate);
            writeInt16LE(outputStream, (short) blockAlign);
            writeInt16LE(outputStream, (short) bitsPerSample);
            writeAscii(outputStream, "data");
            writeInt32LE(outputStream, dataSize);

            for (int sampleIndex = 0; sampleIndex < sampleCount; sampleIndex++) {
                for (int channelIndex = 0; channelIndex < channelCount; channelIndex++) {
                    float clamped = Math.max(-1.0f, Math.min(1.0f, channels[channelIndex][sampleIndex]));
                    short pcm16 = (short) Math.round(clamped * 32767.0f);
                    writeInt16LE(outputStream, pcm16);
                }
            }
        }
    }

    private static void writeAscii(OutputStream outputStream, String text) throws IOException {
        outputStream.write(text.getBytes(StandardCharsets.US_ASCII));
    }

    private static void writeInt16LE(OutputStream outputStream, short value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(2).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putShort(value);
        outputStream.write(buffer.array());
    }

    private static void writeInt32LE(OutputStream outputStream, int value) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN);
        buffer.putInt(value);
        outputStream.write(buffer.array());
    }
}
