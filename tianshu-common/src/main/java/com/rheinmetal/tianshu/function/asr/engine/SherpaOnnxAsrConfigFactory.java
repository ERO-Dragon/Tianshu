package com.rheinmetal.tianshu.function.asr.engine;

import com.k2fsa.sherpa.onnx.OfflineDolphinModelConfig;
import com.k2fsa.sherpa.onnx.OfflineFunAsrNanoModelConfig;
import com.k2fsa.sherpa.onnx.OfflineModelConfig;
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OfflineSenseVoiceModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWenetCtcModelConfig;
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig;
import com.k2fsa.sherpa.onnx.OfflineZipformerCtcModelConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.core.runtime.InferenceResourcePolicy;
import com.rheinmetal.tianshu.model.AsrModelInfo;
import com.rheinmetal.tianshu.model.ModelSettings;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

public final class SherpaOnnxAsrConfigFactory {
    private final IGameEnvironment env;
    private final InferenceResourcePolicy resourcePolicy;

    public SherpaOnnxAsrConfigFactory(IGameEnvironment env) {
        this(env, InferenceResourcePolicy.systemDefault());
    }

    public SherpaOnnxAsrConfigFactory(IGameEnvironment env, InferenceResourcePolicy resourcePolicy) {
        this.env = env;
        this.resourcePolicy = resourcePolicy == null ? InferenceResourcePolicy.systemDefault() : resourcePolicy;
    }

    public Optional<ResolvedConfig> build(AsrModelInfo info, Path modelDir, Path hotwordsFile) {
        if (info == null || modelDir == null) {
            return Optional.empty();
        }
        ModelArchitecture architecture = ModelArchitecture.from(info.architecture());
        ModelFileResolver files = new ModelFileResolver(info, modelDir, env);
        return switch (architecture) {
            case TRANSDUCER -> info.isStreamingModel()
                    ? buildOnlineTransducer(info, files, modelDir, hotwordsFile)
                    : buildOfflineTransducer(info, files, modelDir, hotwordsFile);
            case PARAFORMER -> info.isStreamingModel()
                    ? buildOnlineParaformer(info, files)
                    : buildOfflineParaformer(info, files);
            case ZIPFORMER_CTC -> buildOfflineOnly(info, architecture, () -> buildZipformerCtc(info, files));
            case WENET_CTC -> buildOfflineOnly(info, architecture, () -> buildWenetCtc(info, files));
            case NEMO_CTC -> buildOfflineOnly(info, architecture, () -> buildNemo(info, files));
            case WHISPER -> buildOfflineOnly(info, architecture, () -> buildWhisper(info, files));
            case SENSEVOICE -> buildOfflineOnly(info, architecture, () -> buildSenseVoice(info, files));
            case FUNASR_NANO -> buildOfflineOnly(info, architecture, () -> buildFunAsrNano(info, files));
            case DOLPHIN -> buildOfflineOnly(info, architecture, () -> buildDolphin(info, files));
            case UNKNOWN -> {
                env.error("Unsupported ASR architecture: " + info.architecture() + ", model=" + info.getDisplayName(), null);
                yield Optional.empty();
            }
        };
    }

    private Optional<ResolvedConfig> buildOfflineOnly(
            AsrModelInfo info,
            ModelArchitecture architecture,
            java.util.function.Supplier<Optional<ResolvedConfig>> builder
    ) {
        if (info.isStreamingModel()) {
            env.error("ASR architecture does not support streaming model mode: "
                    + architecture.value + ", model=" + info.getDisplayName(), null);
            return Optional.empty();
        }
        return builder.get();
    }

    private Optional<ResolvedConfig> buildOnlineTransducer(AsrModelInfo info, ModelFileResolver files, Path modelDir, Path hotwordsFile) {
        String encoder = files.require("encoder", ".onnx");
        String decoder = files.require("decoder", ".onnx");
        String joiner = files.require("joiner", ".onnx");
        String tokens = files.require("tokens", ".txt");
        if (encoder == null || decoder == null || joiner == null || tokens == null) {
            return Optional.empty();
        }
        OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .setJoiner(joiner)
                .build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build();
        OnlineRecognizerConfig.Builder configBuilder = OnlineRecognizerConfig.builder()
                .setDecodingMethod("modified_beam_search")
                .setOnlineModelConfig(modelConfig);
        configureOnlineTransducerHotwords(configBuilder, modelDir, hotwordsFile);
        return Optional.of(ResolvedConfig.online("online-transducer", configBuilder.build()));
    }

    private Optional<ResolvedConfig> buildOfflineTransducer(AsrModelInfo info, ModelFileResolver files, Path modelDir, Path hotwordsFile) {
        String encoder = files.require("encoder", ".onnx");
        String decoder = files.require("decoder", ".onnx");
        String joiner = files.require("joiner", ".onnx");
        String tokens = files.require("tokens", ".txt");
        if (encoder == null || decoder == null || joiner == null || tokens == null) {
            return Optional.empty();
        }
        OfflineTransducerModelConfig transducer = OfflineTransducerModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .setJoiner(joiner)
                .build();
        return buildOffline("offline-transducer", OfflineModelConfig.builder()
                .setTransducer(transducer)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build(), builder -> configureOfflineTransducerHotwords(builder, modelDir, hotwordsFile));
    }

    private Optional<ResolvedConfig> buildOfflineParaformer(AsrModelInfo info, ModelFileResolver files) {
        String singleModel = files.find("model", ".onnx", "paraformer");
        String tokens = files.require("tokens", ".txt");
        if (singleModel != null && tokens != null) {
            OfflineParaformerModelConfig paraformer = OfflineParaformerModelConfig.builder()
                    .setModel(singleModel)
                    .build();
            return buildOffline("offline-paraformer", OfflineModelConfig.builder()
                    .setParaformer(paraformer)
                    .setTokens(tokens)
                    .setNumThreads(threadsFor(info))
                    .setDebug(true)
                    .build());
        }
        return Optional.empty();
    }

    private Optional<ResolvedConfig> buildOnlineParaformer(AsrModelInfo info, ModelFileResolver files) {
        String tokens = files.require("tokens", ".txt");
        String encoder = files.require("encoder", ".onnx");
        String decoder = files.require("decoder", ".onnx");
        if (encoder == null || decoder == null || tokens == null) {
            return Optional.empty();
        }
        OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .build();
        OnlineModelConfig modelConfig = OnlineModelConfig.builder()
                .setParaformer(paraformer)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build();
        OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                .setDecodingMethod("modified_beam_search")
                .setOnlineModelConfig(modelConfig)
                .build();
        return Optional.of(ResolvedConfig.online("online-paraformer", config));
    }

    private Optional<ResolvedConfig> buildZipformerCtc(AsrModelInfo info, ModelFileResolver files) {
        String model = files.requireAnyModel();
        String tokens = files.require("tokens", ".txt");
        if (model == null || tokens == null) {
            return Optional.empty();
        }
        OfflineZipformerCtcModelConfig ctc = OfflineZipformerCtcModelConfig.builder()
                .setModel(model)
                .build();
        return buildOffline("offline-zipformer-ctc", OfflineModelConfig.builder()
                .setZipformerCtc(ctc)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildWenetCtc(AsrModelInfo info, ModelFileResolver files) {
        String model = files.requireAnyModel();
        String tokens = files.require("tokens", ".txt");
        if (model == null || tokens == null) {
            return Optional.empty();
        }
        OfflineWenetCtcModelConfig ctc = OfflineWenetCtcModelConfig.builder()
                .setModel(model)
                .build();
        return buildOffline("offline-wenet-ctc", OfflineModelConfig.builder()
                .setWenetCtc(ctc)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildNemo(AsrModelInfo info, ModelFileResolver files) {
        String model = files.requireAnyModel();
        String tokens = files.require("tokens", ".txt");
        if (model == null || tokens == null) {
            return Optional.empty();
        }
        OfflineNemoEncDecCtcModelConfig nemo = OfflineNemoEncDecCtcModelConfig.builder()
                .setModel(model)
                .build();
        return buildOffline("offline-nemo-ctc", OfflineModelConfig.builder()
                .setNemo(nemo)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildWhisper(AsrModelInfo info, ModelFileResolver files) {
        String encoder = files.require("encoder", ".onnx");
        String decoder = files.require("decoder", ".onnx");
        String tokens = files.require("tokens", ".txt");
        if (encoder == null || decoder == null || tokens == null) {
            return Optional.empty();
        }
        OfflineWhisperModelConfig whisper = OfflineWhisperModelConfig.builder()
                .setEncoder(encoder)
                .setDecoder(decoder)
                .setLanguage(resolveLanguage(info))
                .setTask("transcribe")
                .build();
        return buildOffline("offline-whisper", OfflineModelConfig.builder()
                .setWhisper(whisper)
                .setTokens(tokens)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildSenseVoice(AsrModelInfo info, ModelFileResolver files) {
        String model = files.requireAnyModel();
        if (model == null) {
            return Optional.empty();
        }
        OfflineSenseVoiceModelConfig senseVoice = OfflineSenseVoiceModelConfig.builder()
                .setModel(model)
                .setLanguage(resolveLanguage(info))
                .setInverseTextNormalization(true)
                .build();
        return buildOffline("offline-sensevoice", OfflineModelConfig.builder()
                .setSenseVoice(senseVoice)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildFunAsrNano(AsrModelInfo info, ModelFileResolver files) {
        String encoderAdaptor = files.require("encoder", ".onnx", "encoder-adaptor", "encoder_adaptor");
        String llm = files.require("llm", ".onnx", "model");
        String embedding = files.require("embedding", ".onnx");
        String tokenizer = files.require("tokenizer", null);
        if (encoderAdaptor == null || llm == null || embedding == null || tokenizer == null) {
            return Optional.empty();
        }
        OfflineFunAsrNanoModelConfig.Builder builder = OfflineFunAsrNanoModelConfig.builder()
                .setEncoderAdaptor(encoderAdaptor)
                .setLLM(llm)
                .setEmbedding(embedding)
                .setTokenizer(tokenizer)
                .setLanguage(resolveLanguage(info))
                .setItn(true);
        return buildOffline("offline-funasr-nano", OfflineModelConfig.builder()
                .setFunAsrNano(builder.build())
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildDolphin(AsrModelInfo info, ModelFileResolver files) {
        String model = files.requireAnyModel();
        if (model == null) {
            return Optional.empty();
        }
        OfflineDolphinModelConfig dolphin = OfflineDolphinModelConfig.builder()
                .setModel(model)
                .build();
        return buildOffline("offline-dolphin", OfflineModelConfig.builder()
                .setDolphin(dolphin)
                .setNumThreads(threadsFor(info))
                .setDebug(true)
                .build());
    }

    private Optional<ResolvedConfig> buildOffline(String kind, OfflineModelConfig modelConfig) {
        return buildOffline(kind, modelConfig, builder -> {
        });
    }

    private Optional<ResolvedConfig> buildOffline(
            String kind,
            OfflineModelConfig modelConfig,
            Consumer<OfflineRecognizerConfig.Builder> recognizerCustomizer
    ) {
        OfflineRecognizerConfig.Builder builder = OfflineRecognizerConfig.builder()
                .setDecodingMethod("modified_beam_search")
                .setOfflineModelConfig(modelConfig);
        recognizerCustomizer.accept(builder);
        return Optional.of(ResolvedConfig.offline(kind, builder.build()));
    }

    private boolean configureOnlineTransducerHotwords(OnlineRecognizerConfig.Builder builder, Path modelDir, Path hotwordsFile) {
        Optional<Path> resolvedHotwordsFile = resolveHotwordsFile(hotwordsFile);
        if (resolvedHotwordsFile.isEmpty()) {
            return false;
        }
        ModelSettings.AsrSettings settings = ModelSettings.loadAsrSettings(modelDir);
        builder.setHotwordsFile(resolvedHotwordsFile.get().toString())
                .setHotwordsScore((float) settings.hotwordsScore);
        return true;
    }

    private boolean configureOfflineTransducerHotwords(OfflineRecognizerConfig.Builder builder, Path modelDir, Path hotwordsFile) {
        Optional<Path> resolvedHotwordsFile = resolveHotwordsFile(hotwordsFile);
        if (resolvedHotwordsFile.isEmpty()) {
            return false;
        }
        ModelSettings.AsrSettings settings = ModelSettings.loadAsrSettings(modelDir);
        builder.setHotwordsFile(resolvedHotwordsFile.get().toString())
                .setHotwordsScore((float) settings.hotwordsScore);
        return true;
    }

    private Optional<Path> resolveHotwordsFile(Path hotwordsFile) {
        if (hotwordsFile == null || !Files.isRegularFile(hotwordsFile)) {
            return Optional.empty();
        }
        return Optional.of(hotwordsFile.toAbsolutePath().normalize());
    }

    private int threadsFor(AsrModelInfo info) {
        return resourcePolicy.sherpaAsrThreads(
                info != null && info.isStreamingModel(),
                info == null ? "" : info.architecture(),
                info == null ? "" : info.name
        );
    }

    private String resolveLanguage(AsrModelInfo info) {
        for (String lang : info.getLang()) {
            if (lang != null && !lang.isBlank()) {
                return lang.trim().toLowerCase(Locale.ROOT);
            }
        }
        return "zh";
    }

    public record ResolvedConfig(
            OnlineRecognizerConfig onlineConfig,
            OfflineRecognizerConfig offlineConfig,
            boolean offline,
            String kind
    ) {
        static ResolvedConfig online(String kind, OnlineRecognizerConfig config) {
            return new ResolvedConfig(config, null, false, kind);
        }

        static ResolvedConfig offline(String kind, OfflineRecognizerConfig config) {
            return new ResolvedConfig(null, config, true, kind);
        }
    }

    private static final class ModelFileResolver {
        private final AsrModelInfo info;
        private final Path modelDir;
        private final IGameEnvironment env;
        private final Map<String, String> roles;

        private ModelFileResolver(AsrModelInfo info, Path modelDir, IGameEnvironment env) {
            this.info = info;
            this.modelDir = modelDir;
            this.env = env;
            this.roles = info.getFileRoles();
        }

        private String require(String role, String extension, String... aliases) {
            String found = find(role, extension, aliases);
            if (found == null) {
                env.error("ASR model [" + info.getDisplayName() + "] is missing required file role: "
                        + role + ", extension=" + extension, null);
            }
            return found;
        }

        private String requireAnyModel() {
            String model = find("model", ".onnx");
            if (model != null) {
                return model;
            }
            String found = findByName(null, ".onnx");
            if (found == null) {
                env.error("ASR model [" + info.getDisplayName() + "] is missing required model file", null);
            }
            return found;
        }

        private String find(String role, String extension, String... aliases) {
            String explicit = explicitRole(role);
            if (explicit != null) {
                return resolveExplicit(role, explicit, extension);
            }
            for (String alias : aliases) {
                explicit = explicitRole(alias);
                if (explicit != null) {
                    return resolveExplicit(alias, explicit, extension);
                }
            }
            return findByName(role, extension, aliases);
        }

        private String explicitRole(String role) {
            if (role == null || role.isBlank()) {
                return null;
            }
            return roles.get(role.trim().toLowerCase(Locale.ROOT));
        }

        private String resolveExplicit(String role, String file, String extension) {
            if (extension != null && !fileName(file).toLowerCase(Locale.ROOT).endsWith(extension.toLowerCase(Locale.ROOT))) {
                env.error("ASR model [" + info.getDisplayName() + "] fileRoles." + role
                        + " has unexpected extension: " + file + ", expected=" + extension, null);
                return null;
            }
            Path path = modelDir.resolve(file).normalize();
            if (!path.toAbsolutePath().normalize().startsWith(modelDir.toAbsolutePath().normalize())) {
                env.error("ASR model [" + info.getDisplayName() + "] fileRoles." + role
                        + " points outside model directory: " + file, null);
                return null;
            }
            if (!Files.isRegularFile(path)) {
                env.error("ASR model [" + info.getDisplayName() + "] fileRoles." + role
                        + " file does not exist: " + file, null);
                return null;
            }
            return path.toAbsolutePath().toString();
        }

        private String findByName(String keyword, String extension, String... aliases) {
            List<String> candidates = info.getModelFiles();
            for (String candidate : candidates) {
                if (matches(candidate, keyword, extension, aliases)) {
                    Path path = modelDir.resolve(candidate).normalize();
                    if (Files.isRegularFile(path)) {
                        return path.toAbsolutePath().toString();
                    }
                }
            }
            return null;
        }

        private boolean matches(String file, String keyword, String extension, String... aliases) {
            if (file == null) {
                return false;
            }
            String name = fileName(file).toLowerCase(Locale.ROOT);
            if (extension != null && !name.endsWith(extension.toLowerCase(Locale.ROOT))) {
                return false;
            }
            if (keyword == null || keyword.isBlank()) {
                return true;
            }
            String normalizedKeyword = keyword.toLowerCase(Locale.ROOT);
            if (name.contains(normalizedKeyword)) {
                return true;
            }
            for (String alias : aliases) {
                if (alias != null && !alias.isBlank() && name.contains(alias.toLowerCase(Locale.ROOT))) {
                    return true;
                }
            }
            return false;
        }

        private String fileName(String path) {
            String normalized = path.replace('\\', '/');
            int index = normalized.lastIndexOf('/');
            return index >= 0 ? normalized.substring(index + 1) : normalized;
        }
    }

    private enum ModelArchitecture {
        TRANSDUCER("transducer"),
        PARAFORMER("paraformer"),
        ZIPFORMER_CTC("zipformer-ctc"),
        WENET_CTC("wenet-ctc"),
        NEMO_CTC("nemo-ctc"),
        WHISPER("whisper"),
        SENSEVOICE("sensevoice"),
        FUNASR_NANO("funasr-nano"),
        DOLPHIN("dolphin"),
        UNKNOWN("");

        private final String value;

        ModelArchitecture(String value) {
            this.value = value;
        }

        static ModelArchitecture from(String value) {
            if (value == null || value.isBlank()) {
                return UNKNOWN;
            }
            String normalized = value.trim().toLowerCase(Locale.ROOT);
            for (ModelArchitecture architecture : values()) {
                if (architecture.value.equals(normalized)) {
                    return architecture;
                }
            }
            return UNKNOWN;
        }
    }
}
