package com.rheinmetal.tianshu.function.tts.synthesis.moss;

import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.model.HuggingFaceDownloader;
import com.sentencepiece.SentencePieceProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

final class MossModelRuntime implements AutoCloseable {
    private static final String TTS_REPO_ID = "OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX";
    private static final String CODEC_REPO_ID = "OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX";
    private static final String REVISION = "main";
    private static final Gson GSON = new Gson();

    private final IGameEnvironment env;
    private final HuggingFaceDownloader downloader;
    private final Path modelRootDir;
    private final InferenceResourcePolicy resourcePolicy;
    private final Map<String, OrtSession> sessions = new HashMap<>();
    private OrtEnvironment environment;
    private SentencePieceProcessor tokenizer;
    private JsonObject manifest;
    private JsonObject ttsMeta;
    private JsonObject codecMeta;

    MossModelRuntime(IGameEnvironment env, HuggingFaceDownloader downloader, Path modelRootDir,
                     InferenceResourcePolicy resourcePolicy) {
        this.env = env;
        this.downloader = downloader;
        this.modelRootDir = modelRootDir;
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
    }

    synchronized void initialize() throws Exception {
        if (!sessions.isEmpty()) {
            return;
        }
        if (findManifestPath() == null) {
            downloadModels();
        }
        loadManifestAndMeta();
        environment = OrtEnvironment.getEnvironment();
        initTokenizer();
        initSessions();
    }

    OrtEnvironment environment() {
        return require(environment, "ORT environment");
    }

    OrtSession requireSession(String name) {
        OrtSession session = sessions.get(name);
        if (session == null) {
            throw new IllegalStateException("MOSS session is unavailable: " + name);
        }
        return session;
    }

    boolean hasSession(String name) {
        return sessions.containsKey(name);
    }

    SentencePieceProcessor tokenizer() {
        return require(tokenizer, "tokenizer");
    }

    JsonObject manifest() {
        return require(manifest, "manifest");
    }

    JsonObject ttsMeta() {
        return require(ttsMeta, "TTS metadata");
    }

    JsonObject codecMeta() {
        return require(codecMeta, "codec metadata");
    }

    int sampleRate() {
        return codecMeta != null && codecMeta.has("codec_config")
                ? codecMeta.getAsJsonObject("codec_config").get("sample_rate").getAsInt()
                : 48_000;
    }

    private void downloadModels() throws Exception {
        env.info("moss.model.download.started");
        downloader.downloadModelFiles(TTS_REPO_ID, modelRootDir, REVISION, true, 3);
        downloader.downloadModelFiles(CODEC_REPO_ID, modelRootDir, REVISION, true, 3);
        env.info("moss.model.download.completed");
    }

    private void loadManifestAndMeta() throws IOException {
        Path manifestPath = resolveManifestPath();
        manifest = GSON.fromJson(Files.readString(manifestPath), JsonObject.class);
        Path ttsMetaPath = resolveManifestRelativePath(manifest.getAsJsonObject("model_files").get("tts_meta").getAsString());
        Path codecMetaPath = resolveManifestRelativePath(manifest.getAsJsonObject("model_files").get("codec_meta").getAsString());
        ttsMeta = GSON.fromJson(Files.readString(ttsMetaPath), JsonObject.class);
        codecMeta = GSON.fromJson(Files.readString(codecMetaPath), JsonObject.class);
    }

    private void initTokenizer() throws IOException {
        String relativePath = manifest.getAsJsonObject("model_files").has("tokenizer_model")
                ? manifest.getAsJsonObject("model_files").get("tokenizer_model").getAsString()
                : "tokenizer.model";
        tokenizer = new SentencePieceProcessor(resolveManifestRelativePath(relativePath));
    }

    private void initSessions() throws Exception {
        JsonObject ttsFiles = ttsMeta.getAsJsonObject("files");
        JsonObject codecFiles = codecMeta.getAsJsonObject("files");
        addSession("prefill", ttsFiles, "prefill");
        addSession("decode", ttsFiles, "decode_step");
        addSession("local_decoder", ttsFiles, "local_decoder");
        addOptionalSession("local_greedy_frame", ttsFiles, "local_greedy_frame");
        addOptionalSession("local_fixed_sampled_frame", ttsFiles, "local_fixed_sampled_frame");
        addOptionalSession("local_cached_step", ttsFiles, "local_cached_step");
        addSession("codec_encode", codecFiles, "encode");
        addSession("codec_decode", codecFiles, "decode_full");
        addOptionalSession("codec_decode_step", codecFiles, "decode_step");
    }

    private void addSession(String name, JsonObject files, String fileKey) throws OrtException {
        sessions.put(name, createSession(modelRootDir.resolve(files.get(fileKey).getAsString())));
    }

    private void addOptionalSession(String name, JsonObject files, String fileKey) throws OrtException {
        if (files.has(fileKey) && !files.get(fileKey).isJsonNull()) {
            addSession(name, files, fileKey);
        }
    }

    private OrtSession createSession(Path modelPath) throws OrtException {
        try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
            options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
            options.setIntraOpNumThreads(resourcePolicy.mossTtsThreads());
            options.setInterOpNumThreads(1);
            return environment().createSession(modelPath.toString(), options);
        }
    }

    private Path resolveManifestPath() {
        Path path = findManifestPath();
        if (path != null) {
            return path;
        }
        throw new IllegalStateException("MOSS manifest not found under model path: " + modelRootDir.toAbsolutePath().normalize());
    }

    private Path findManifestPath() {
        List<Path> candidates = List.of(
                modelRootDir.resolve("browser_poc_manifest.json"),
                modelRootDir.resolve("MOSS-TTS-Nano-100M-ONNX").resolve("browser_poc_manifest.json"),
                modelRootDir.resolve("MOSS-TTS-Nano-ONNX-CPU").resolve("browser_poc_manifest.json")
        );
        return candidates.stream().filter(Files::isRegularFile).findFirst().orElse(null);
    }

    private Path resolveManifestRelativePath(String relativePath) {
        Path relative = Paths.get(relativePath);
        Path resolved = resolveManifestPath().getParent().resolve(relative).normalize();
        if (Files.exists(resolved)) {
            return resolved;
        }
        Path fileName = relative.getFileName();
        if (fileName != null) {
            Path found = searchFileInRoot(fileName.toString());
            if (found != null) {
                return found;
            }
        }
        return resolved;
    }

    private Path searchFileInRoot(String targetFileName) {
        try (var files = Files.walk(modelRootDir)) {
            return files.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().equals(targetFileName))
                    .findFirst()
                    .orElse(null);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Override
    public synchronized void close() {
        for (OrtSession session : sessions.values()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        sessions.clear();
        if (environment != null) {
            try {
                environment.close();
            } catch (Exception ignored) {
            }
        }
        environment = null;
        tokenizer = null;
        manifest = null;
        ttsMeta = null;
        codecMeta = null;
    }

    private static <T> T require(T value, String resource) {
        if (value == null) {
            throw new IllegalStateException("MOSS " + resource + " is not initialized");
        }
        return value;
    }
}
