package com.rheinmetal.tianshu.function.tts.voice;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

public final class TtsReferenceAudioLoader {
    public TtsReferenceAudio loadMono(Path path) throws Exception {
        if (path == null || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("reference audio file is missing");
        }
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(path.toFile())) {
            AudioFormat sourceFormat = sourceStream.getFormat();
            AudioFormat pcmFormat = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    Math.max(1, sourceFormat.getChannels()),
                    Math.max(1, sourceFormat.getChannels()) * 2,
                    sourceFormat.getSampleRate(),
                    false
            );
            try (AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcmFormat, sourceStream)) {
                byte[] pcm = readAll(pcmStream);
                int channels = Math.max(1, pcmFormat.getChannels());
                int frames = pcm.length / (channels * 2);
                float[] mono = new float[frames];
                for (int frame = 0; frame < frames; frame++) {
                    float sum = 0.0f;
                    for (int channel = 0; channel < channels; channel++) {
                        int offset = (frame * channels + channel) * 2;
                        int low = pcm[offset] & 0xFF;
                        int high = pcm[offset + 1];
                        short value = (short) ((high << 8) | low);
                        sum += value / 32768.0f;
                    }
                    mono[frame] = sum / channels;
                }
                return new TtsReferenceAudio(mono, Math.round(pcmFormat.getSampleRate()));
            }
        }
    }

    private static byte[] readAll(AudioInputStream stream) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = stream.read(buffer)) >= 0) {
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }
}
