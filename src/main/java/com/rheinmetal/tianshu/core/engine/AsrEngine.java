package com.rheinmetal.tianshu.core.engine;

import com.rheinmetal.tianshu.Tianshu;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.k2fsa.sherpa.onnx.OnlineParaformerModelConfig;

import java.io.File;
import java.util.concurrent.locks.ReentrantLock;

public class AsrEngine {

    private final ReentrantLock streamLock = new ReentrantLock();
    private OnlineRecognizer recognizer;
    private OnlineStream stream;

    private File findModelFile(File dir, String keyword, String extension) {
        if (dir == null || !dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        
        for (File file : files) {
            String fileName = file.getName().toLowerCase();
            if (file.isFile() && fileName.contains(keyword.toLowerCase()) && fileName.endsWith(extension)) {
                return file;
            }
        }
        return null;
    }
    
    public void initialize(String modelDir) {
        Tianshu.LOGGER.info("初始化 ASR 引擎，模型目录: {}", modelDir);
        File dir = new File(modelDir);

        try {
            // 查找模型文件
            File encoder = findModelFile(dir, "encoder", ".onnx");
            File decoder = findModelFile(dir, "decoder", ".onnx");
            File joiner = findModelFile(dir, "joiner", ".onnx");
            File tokensFile = findModelFile(dir, "tokens", ".txt");

            if (encoder == null || decoder == null || tokensFile == null) {
                Tianshu.LOGGER.error("找不到必要的模型文件！");
                return;
            }

            Tianshu.LOGGER.info("使用模型文件:");
            Tianshu.LOGGER.info("  Encoder: {}", encoder.getAbsolutePath());
            Tianshu.LOGGER.info("  Decoder: {}", decoder.getAbsolutePath());
            if (joiner != null) {
                Tianshu.LOGGER.info("  Joiner: {}", joiner.getAbsolutePath());
            }
            Tianshu.LOGGER.info("  Tokens: {}", tokensFile.getAbsolutePath());

            OnlineModelConfig modelConfig;
            if (joiner != null) {
                Tianshu.LOGGER.info("检测到 Transducer 流式模型");
                OnlineTransducerModelConfig transducer = OnlineTransducerModelConfig.builder()
                        .setEncoder(encoder.getAbsolutePath())
                        .setDecoder(decoder.getAbsolutePath())
                        .setJoiner(joiner.getAbsolutePath())
                        .build();

                modelConfig = OnlineModelConfig.builder()
                        .setTransducer(transducer)
                        .setTokens(tokensFile.getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(true)
                        .build();
            } else {
                Tianshu.LOGGER.info("检测到 Paraformer 流式模型");
                OnlineParaformerModelConfig paraformer = OnlineParaformerModelConfig.builder()
                        .setEncoder(encoder.getAbsolutePath())
                        .setDecoder(decoder.getAbsolutePath())
                        .build();

                modelConfig = OnlineModelConfig.builder()
                        .setParaformer(paraformer)
                        .setTokens(tokensFile.getAbsolutePath())
                        .setNumThreads(2)
                        .setDebug(true)
                        .build();
            }

            OnlineRecognizerConfig config = OnlineRecognizerConfig.builder()
                    .setOnlineModelConfig(modelConfig)
                    .setDecodingMethod("greedy_search")
                    .build();

            Tianshu.LOGGER.info("创建 OnlineRecognizer...");
            recognizer = new OnlineRecognizer(config);
            Tianshu.LOGGER.info("✅ ASR 引擎初始化成功");
        } catch (Throwable t) {
            // 捕获所有异常，包括 Error
            Tianshu.LOGGER.error("ASR 引擎创建失败，请检查模型文件是否损坏", t);
        }
    }

    public OnlineStream createStream() {
        if (recognizer == null) {
            Tianshu.LOGGER.error("ASR 引擎未初始化");
            return null;
        }
        stream = recognizer.createStream();
        return stream;
    }

    public String feedAudio(byte[] pcmData) {
        if (recognizer == null || stream == null) return "";
        streamLock.lock();
        try {
            // 官方推荐的 byte[] 转 float[] 归一化逻辑 (参考 StreamingAsrFromMicTransducer.java)
            float[] samples = new float[pcmData.length / 2];
            for (int i = 0; i != samples.length; ++i) {
                short low = (short) pcmData[2 * i];
                short high = (short) pcmData[2 * i + 1];
                int s = (high << 8) + low;
                samples[i] = (float) s / 32768;
            }

            // 官方喂入与解码逻辑
            stream.acceptWaveform(samples, 16000);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
        } finally {
            streamLock.unlock();
        }
        return recognizer.getResult(stream).getText();
    }

    public boolean isEndpoint() {
        if (recognizer == null || stream == null) return false;
        streamLock.lock();
        try {
            return recognizer.isEndpoint(stream);
        } finally {
            streamLock.unlock();
        }
    }

    public void reset() {
        if (recognizer == null || stream == null) return;
        streamLock.lock();
        try {
            recognizer.reset(stream);
        } finally {
            streamLock.unlock();
        }
    }

    public String recognizeComplete(byte[] fullAudio) {
        if (recognizer == null) return "";
        
        // 必须创建临时流，不能干扰主常驻流
        OnlineStream tempStream = recognizer.createStream();
        
        float[] samples = new float[fullAudio.length / 2];
        for (int i = 0; i != samples.length; ++i) {
            short low = (short) fullAudio[2 * i];
            short high = (short) fullAudio[2 * i + 1];
            int s = (high << 8) + low;
            samples[i] = (float) s / 32768;
        }

        tempStream.acceptWaveform(samples, 16000);
        // 官方技巧：追加 0.8 秒静音强制触发断句 (参考 StreamingDecodeFileTransducer.java)
        tempStream.acceptWaveform(new float[(int) (0.8 * 16000)], 16000);
        
        while (recognizer.isReady(tempStream)) {
            recognizer.decode(tempStream);
        }
        
        String result = recognizer.getResult(tempStream).getText();
        // 【关键】临时流用完必须 release 释放 C++ 内存，绝不能 close()
        tempStream.release(); 
        return result;
    }
    // 在类中新增此方法
    public String forceFlush() {
        if (recognizer == null || stream == null) return "";
        // 喂入 0.3 秒静音，强制把缓冲区的音频挤压出来计算
        String result = "";
        streamLock.lock();
        try {
            stream.acceptWaveform(new float[(int) (0.3 * 16000)], 16000);
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            result = recognizer.getResult(stream).getText();
            // 重置引擎，准备听下一句
            recognizer.reset(stream);
        } finally {
            streamLock.unlock();
        }
        return result;
    }
    public void shutdown() {
        // 【关键】官方示例里的标准释放方式
        if (stream != null) {
            stream.release();
            stream = null;
        }
        if (recognizer != null) {
            recognizer.release(); 
            recognizer = null;
        }
        Tianshu.LOGGER.info("ASR 引擎已安全关闭");
    }
}
