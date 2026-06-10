package com.rheinmetal.tianshu.function.tts.synthesis;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.function.tts.runtime.TtsRequest;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.rheinmetal.tianshu.model.tts.moss.MossTtsService;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public final class MossTtsBackend implements TtsBackend {
    private final IGameEnvironment env;
    private MossTtsService service;
    private boolean initialized;
    private volatile boolean interrupted;
    private int sampleRate;
    private Path voiceSamplePath;
    private CachedMossVoice cachedVoice;

    public MossTtsBackend(IGameEnvironment env, ITianshuConfig config) {
        this.env = env;
    }

    @Override
    public synchronized boolean initialize(TtsResolvedModel model) {
        shutdown();
        try {
            Path modelRootDir = model.modelDir();
            if (!Files.exists(modelRootDir)) {
                Files.createDirectories(modelRootDir);
            }
            HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
            service = new MossTtsService(env, downloader, modelRootDir);
            service.init();
            sampleRate = service.getSampleRate();
            initialized = true;
            env.info("MOSS-TTS backend initialized, sampleRate=" + sampleRate + "Hz");
            return true;
        } catch (Throwable t) {
            env.error("MOSS-TTS backend initialization failed", t);
            shutdown();
            return false;
        }
    }

    @Override
    public boolean isInitialized() {
        return initialized && service != null;
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public void synthesize(TtsRequest request, TtsAudioSink sink) {
        MossTtsService current = service;
        if (!initialized || current == null) {
            env.error("MOSS-TTS backend is not initialized", null);
            return;
        }
        interrupted = false;
        setVoiceSamplePath(request.voiceProfile().voiceSample());
        env.info("MOSS-TTS synthesis started: " + request.text());
        try {
            if (interrupted) {
                env.info("MOSS-TTS synthesis interrupted before synthesis: " + request.text());
                return;
            }
            List<List<Integer>> promptAudioCodes = resolvePromptAudioCodes(current);
            if (interrupted) {
                env.info("MOSS-TTS synthesis interrupted after prompt preparation: " + request.text());
                return;
            }
            current.synthesizeStreaming(request.text(), promptAudioCodes, (audio, chunkIndex, totalChunks) -> {
                if (interrupted) {
                    return;
                }
                byte[] pcm = TtsPcm16AudioConverter.fromChannels(audio);
                if (pcm.length > 0) {
                    sink.accept(pcm);
                }
                if (totalChunks > 0) {
                    env.info("MOSS-TTS sub-chunk " + (chunkIndex + 1) + "/" + totalChunks + " completed");
                } else {
                    env.info("MOSS-TTS stream chunk " + (chunkIndex + 1) + " completed");
                }
            });
            if (interrupted) {
                env.info("MOSS-TTS synthesis interrupted: " + request.text());
            } else {
                env.info("MOSS-TTS synthesis completed: " + request.text());
            }
        } catch (Throwable t) {
            env.error("MOSS-TTS synthesis failed: " + request.text(), t);
        }
    }

    @Override
    public void interrupt() {
        interrupted = true;
    }

    @Override
    public synchronized void shutdown() {
        interrupted = true;
        service = null;
        initialized = false;
        sampleRate = 0;
        cachedVoice = null;
    }

    private void setVoiceSamplePath(String voiceSample) {
        Path normalizedPath = voiceSample == null || voiceSample.isBlank() ? null : Path.of(voiceSample).toAbsolutePath().normalize();
        if (voiceSamplePath == null ? normalizedPath != null : !voiceSamplePath.equals(normalizedPath)) {
            voiceSamplePath = normalizedPath;
            cachedVoice = null;
        }
    }

    private List<List<Integer>> resolvePromptAudioCodes(MossTtsService current) throws Exception {
        MossVoiceSource source = MossVoiceSource.fromPath(voiceSamplePath);
        if (source == null) {
            cachedVoice = null;
            return null;
        }
        CachedMossVoice cached = cachedVoice;
        if (cached != null && cached.matches(source)) {
            return cached.promptAudioCodes();
        }
        env.info("MOSS-TTS encoding selected voice sample: " + source.path());
        List<List<Integer>> promptAudioCodes = current.encodePromptAudioCodes(source.path());
        cachedVoice = new CachedMossVoice(source, deepImmutableCopy(promptAudioCodes));
        env.info("MOSS-TTS selected voice cached, frames=" + cachedVoice.promptAudioCodes().size());
        return cachedVoice.promptAudioCodes();
    }

    private List<List<Integer>> deepImmutableCopy(List<List<Integer>> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }
        List<List<Integer>> copy = new ArrayList<>(codes.size());
        for (List<Integer> frame : codes) {
            copy.add(Collections.unmodifiableList(new ArrayList<>(frame)));
        }
        return Collections.unmodifiableList(copy);
    }

    private record MossVoiceSource(Path path, long lastModifiedMillis, long size) {
        private static MossVoiceSource fromPath(Path path) throws Exception {
            if (path == null) {
                return null;
            }
            Path normalizedPath = path.toAbsolutePath().normalize();
            if (!Files.isRegularFile(normalizedPath)) {
                return null;
            }
            BasicFileAttributes attributes = Files.readAttributes(normalizedPath, BasicFileAttributes.class);
            return new MossVoiceSource(normalizedPath, attributes.lastModifiedTime().toMillis(), attributes.size());
        }
    }

    private record CachedMossVoice(MossVoiceSource source, List<List<Integer>> promptAudioCodes) {
        private boolean matches(MossVoiceSource other) {
            return source.equals(other);
        }
    }
}
