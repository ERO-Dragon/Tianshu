package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.function.asr.settings.AsrConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AXStorageConfiguration;
import com.rheinmetal.tianshu.function.llm.settings.LlmConfiguration;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.protocol.voice.VoiceResourceConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConfigurationPortTest {
    @Test
    void configurationPortsExposeNoMutationOrDiscoveryOperations() {
        for (Class<?> port : List.of(
                AsrConfiguration.class,
                LlmConfiguration.class,
                TtsConfiguration.class,
                AXStorageConfiguration.class,
                VoiceResourceConfiguration.class
        )) {
            assertTrue(port.isInterface(), () -> port.getName() + " must be an interface");
            assertTrue(Arrays.stream(port.getMethods()).noneMatch(method ->
                            method.getName().startsWith("set")
                                    || method.getName().equals("save")
                                    || method.getName().startsWith("find")
                                    || method.getName().startsWith("scan")),
                    () -> port.getName() + " must remain a read-only value/path port");
        }
    }
}
