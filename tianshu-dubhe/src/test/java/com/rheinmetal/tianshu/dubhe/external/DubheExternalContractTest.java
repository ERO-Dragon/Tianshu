package com.rheinmetal.tianshu.dubhe.external;

import com.rheinmetal.tianshu.api.capability.CapabilityCatalog;
import com.rheinmetal.tianshu.api.capability.CapabilityId;
import com.rheinmetal.tianshu.dubhe.agent.delegate.DubheDelegatePromptRepository;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguage;
import com.rheinmetal.tianshu.dubhe.dialogue.system.DubhePromptLanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对外契约的硬边界（G10）：只有两个具体能力，没有"提交任务给我规划"的通用入口。
 *
 * <p>这是契约测试，不是行为测试：它检查能力表与源码字面量，所以新增一个对外入口会同时让这里变红，
 * 迫使改动者先改契约。</p>
 */
class DubheExternalContractTest {

    @AfterEach
    void cleanUp() {
        DubheExternalCapabilities.unregister();
    }

    @Test
    void dubheExposesExactlySearchAndRelay() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("contract test never calls the model");
        }, prompts());

        List<String> exposed = CapabilityCatalog.list().stream()
                .filter(descriptor -> "dubhe".equals(descriptor.provider()))
                .map(descriptor -> descriptor.id().toString())
                .sorted()
                .toList();

        assertEquals(List.of("dubhe:relay", "dubhe:search"), exposed,
                "the external surface is a closed set: research and relay only");
    }

    @Test
    void theExternalRowsAreFunctionsNotAgentSkills() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("contract test never calls the model");
        }, prompts());

        for (var descriptor : CapabilityCatalog.list()) {
            if (!"dubhe".equals(descriptor.provider())) {
                continue;
            }
            assertEquals("function", descriptor.kind().value(),
                    "external rows are functions; agent skills are never exposed: " + descriptor.id());
        }
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "diplomacy")).isEmpty(),
                "the diplomacy skill is internal and must never appear as a capability");
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "search")).isPresent());
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "relay")).isPresent());
    }

    @Test
    void noGenericTaskEntryExistsAnywhere() {
        DubheExternalCapabilities.register((payload, handler, timeout) -> {
            throw new AssertionError("contract test never calls the model");
        }, prompts());

        for (var descriptor : CapabilityCatalog.list()) {
            String id = descriptor.id().toString().toLowerCase(Locale.ROOT);
            assertFalse(id.contains("agent_task"), "generic entry found: " + id);
            assertFalse(id.contains("generic_task"), "generic entry found: " + id);
            assertFalse(id.endsWith(":task"), "a bare task entry is a generic entry: " + id);
        }
        assertTrue(CapabilityCatalog.find(CapabilityId.of("dubhe", "agent_task")).isEmpty());
    }

    @Test
    void theRetiredLiteralIsGoneFromTheSources() throws Exception {
        Path root = Path.of("src/main/java/com/rheinmetal/tianshu/dubhe");
        assertTrue(Files.isDirectory(root), "run this test from the module directory");
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                assertFalse(source.contains("AGENT_TASK"),
                        () -> "the retired generic entry is still named in " + file);
            }
        }
    }

    private static DubheDelegatePromptRepository prompts() {
        return new DubheDelegatePromptRepository(
                DubhePromptLanguageProvider.fixed(DubhePromptLanguage.ZH_CN));
    }
}
