package com.rheinmetal.tianshu.function.llm.rag;

import com.rheinmetal.tianshu.core.scope.WorldScope;
import com.rheinmetal.tianshu.core.scope.WorldScopeKind;
import com.rheinmetal.tianshu.function.llm.TestLlmSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LlmRagPathResolverTest {
    @TempDir
    Path tempDir;

    @Test
    void resolvesSafeWorldModuleAndAgentLayout() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        LlmRagPathResolver resolver = new LlmRagPathResolver(
                config,
                () -> new WorldScope("user", "world:nether/test", "Nether", WorldScopeKind.LOCAL_WORLD, true)
        );

        LlmRagPathResolution resolution = resolver.resolveCurrent("module:ax/test", "agent main");

        assertEquals("world_nether_test", resolution.worldId());
        assertEquals("module_ax_test", resolution.moduleId());
        assertEquals("agent_main", resolution.agentId());
        assertEquals("module_ax_test/agent_main", resolution.profile());
        assertEquals(config.getLlmRagRootPath().resolve("world_nether_test"), resolution.worldRoot());
        assertEquals(resolution.agentRoot().resolve("memory_rag").resolve("memories.jsonl"), resolution.memoriesFile());
    }

    @Test
    void usesUnknownWorldWhenProviderIsMissing() {
        TestLlmSupport.FakeConfig config = new TestLlmSupport.FakeConfig(tempDir);
        LlmRagPathResolver resolver = new LlmRagPathResolver(config, null);

        LlmRagPathResolution resolution = resolver.resolveCurrent("", "");

        assertEquals("unknown_world", resolution.worldId());
        assertEquals("unknown_module", resolution.moduleId());
        assertEquals("default_agent", resolution.agentId());
    }
}
