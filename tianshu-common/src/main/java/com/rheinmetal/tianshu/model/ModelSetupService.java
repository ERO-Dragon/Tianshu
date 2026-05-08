package com.rheinmetal.tianshu.model;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.api.ITianshuConfig;
import com.rheinmetal.tianshu.constant.ModelPresets;
import com.rheinmetal.tianshu.constant.VramTier;
import com.rheinmetal.tianshu.function.asr.AsrModelService;
import com.rheinmetal.tianshu.protocol.runtime.ExecutionLane;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolExecutorManager;
import com.rheinmetal.tianshu.protocol.runtime.ProtocolTaskSpec;

import java.nio.file.Path;
import java.util.function.Supplier;

public class ModelSetupService {

    public interface DownloadProgressCallback {
        void onProgress(String label, int percent);
        void onComplete();
        void onError(String message);
    }

    private static final String DEFAULT_GITHUB_PROXY_URL = "https://gh-proxy.org/";

    private final IGameEnvironment env;
    private final ITianshuConfig config;
    private final ProtocolExecutorManager executorManager;
    private final Supplier<AsrModelService> asrModelServiceSupplier;
    private volatile boolean downloadCancelled = false;
    private volatile boolean downloadPaused = false;

    public ModelSetupService(IGameEnvironment env, ITianshuConfig config, ProtocolExecutorManager executorManager, Supplier<AsrModelService> asrModelServiceSupplier) {
        this.env = env;
        this.config = config;
        this.executorManager = executorManager;
        this.asrModelServiceSupplier = asrModelServiceSupplier;
    }

    public boolean isDownloadPaused() {
        return downloadPaused;
    }

    public void pauseDownload() {
        downloadPaused = true;
    }

    public void resumeDownload() {
        downloadPaused = false;
    }

    public void cancelDownload() {
        downloadCancelled = true;
        downloadPaused = false;
    }

    public void downloadPresetModels(VramTier tier, DownloadProgressCallback callback) {
        if (tier == VramTier.CUSTOM) {
            callback.onError("自定义预设不支持一键下载");
            return;
        }
        downloadCancelled = false;
        downloadPaused = false;
        executorManager.submit(
                ProtocolTaskSpec.builder()
                        .moduleId("core.model.setup")
                        .lane(ExecutionLane.IO)
                        .concurrencyKey("core.model.setup:preset.download")
                        .maxConcurrency(1)
                        .queueCapacity(1)
                        .build(),
                () -> runPresetDownload(tier, callback)
        );
    }

    private void runPresetDownload(VramTier tier, DownloadProgressCallback callback) {
        try {
            callback.onProgress("ASR:", 5);
            AsrModelInfo asrModel = AsrModelManager.getDefaultModel(tier);
            if (asrModel != null) {
                Path asrDir = config.getAsrBasePath().resolve("model").resolve(asrModel.name);
                AsrModelService asrModelService = asrModelServiceSupplier.get();
                asrModelService.downloadModelSync(asrModel, asrDir, DEFAULT_GITHUB_PROXY_URL, new AsrModelService.DownloadProgressCallback() {
                    @Override
                    public void onProgress(String label, int percent) {
                        callback.onProgress("ASR:", percent);
                    }

                    @Override
                    public void onComplete() {}

                    @Override
                    public void onError(String message) {
                        throw new RuntimeException("ASR 下载失败: " + message);
                    }
                });
            } else {
                HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
                Path asrDir = config.getAsrBasePath().resolve("model").resolve(ModelPresets.getPresetAsrName(tier));
                downloader.downloadModelFiles(ModelPresets.getPresetAsrName(tier).equals("ParaformerOnnx")
                                ? "csukuangfj/sherpa-onnx-paraformer-zh-2023-09-14"
                                : "csukuangfj/sherpa-onnx-zipformer-multi-zh-hans-2023-10-24",
                        asrDir, "main", true, 3);
            }
            callback.onProgress("ASR:", 100);
            checkCancelled();

            callback.onProgress("LLM:", 5);
            HuggingFaceDownloader downloader = new HuggingFaceDownloader(env);
            Path llmDir = config.getLlmBasePath().resolve("model").resolve(ModelPresets.getPresetLlmName(tier));
            downloader.downloadModelFiles(ModelPresets.getPresetTtsModelId(tier).startsWith("OpenMOSS")
                            ? "csukuangfj/sherpa-onnx-vits-zh-hf-keqing"
                            : ModelPresets.getPresetTtsModelId(tier),
                    llmDir, "main", true, 3);
            callback.onProgress("LLM:", 100);
            checkCancelled();

            callback.onProgress("TTS:", 5);
            String ttsName = ModelPresets.getPresetTtsName(tier);
            Path ttsDir = config.getTtsBasePath().resolve("model").resolve(ttsName);
            if (ttsName.contains("MOSS")) {
                downloader.downloadModelFiles("OpenMOSS-Team/MOSS-TTS-Nano-100M-ONNX", ttsDir, "main", true, 3);
                downloader.downloadModelFiles("OpenMOSS-Team/MOSS-Audio-Tokenizer-Nano-ONNX", ttsDir, "main", true, 3);
            } else {
                downloader.downloadModelFiles(ModelPresets.getPresetTtsModelId(tier), ttsDir, "main", true, 3);
            }
            callback.onProgress("TTS:", 100);

            callback.onComplete();
        } catch (Exception e) {
            callback.onError(e.getMessage() != null ? e.getMessage() : "预设模型下载失败");
        }
    }

    private void checkCancelled() {
        while (downloadPaused) {
            if (downloadCancelled) {
                throw new RuntimeException("下载已取消");
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("下载线程被中断", e);
            }
        }
        if (downloadCancelled) {
            throw new RuntimeException("下载已取消");
        }
    }
}
