# Module Configuration Ports Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove `ITianshuConfig` and give Core, ASR, LLM, TTS, AX, and voice resources only their bounded configuration ports without changing GUI or runtime behavior.

**Architecture:** NeoForge remains the writable configuration owner. Common defines one read-only port per functional domain plus a voice-resource path port; the NeoForge composition root passes each module only its own view. Model file discovery moves into domain resolvers, and temporary TTS model activation stops mutating persistent GUI selection.

**Tech Stack:** Java 21+, Gradle 9.3.1, JUnit 5, NeoForge, Gson, existing Tianshu module lifecycle and protocol runtime.

## Global Constraints

- Preserve every current GUI field, default value, save path, protocol payload, capability, topic, and runtime refresh behavior.
- Do not add compatibility constructors or retain `ITianshuConfig` after migration.
- Do not change model inference, ONNX handles, MOSS frame cadence, GPU execution, audio playback, or protocol lane ownership.
- Do not perform directory scans, catalog lookup, download, or file writes on the Minecraft main thread.
- Configuration ports contain no player-visible language text and no no-op setters.
- AX, ASR, LLM, and TTS must not import another functional module's configuration port.
- Existing user changes in the working tree must be preserved; stage and commit only files belonging to the current task.

---

### Task 1: Lock the configuration boundary with failing architecture tests

**Files:**
- Create: `tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleConfigurationBoundaryTest.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleAssemblyRuntimeBoundaryTest.java`

**Interfaces:**
- Consumes: production Java source roots.
- Produces: source-level gates forbidding `ITianshuConfig` in function modules and forbidding `TianshuModuleAssemblyContext.config()`.

- [ ] **Step 1: Write the failing source-boundary test**

```java
@Test
void functionModulesMustNotDependOnAggregateHostConfiguration() throws IOException {
    List<Path> offenders = JavaSourceBoundary.findFilesContaining(
            productionRoot().resolve("com/rheinmetal/tianshu/function"),
            "com.rheinmetal.tianshu.api.ITianshuConfig"
    );
    assertEquals(List.of(), offenders);
}

@Test
void moduleAssemblyContextMustNotExposeConfigurationAggregate() {
    assertFalse(Arrays.stream(TianshuModuleAssemblyContext.class.getRecordComponents())
            .anyMatch(component -> component.getName().equals("config")));
}
```

- [ ] **Step 2: Run the tests and record RED**

Run: `./gradlew :tianshu-common:test --tests "*ModuleConfigurationBoundaryTest" --tests "*ModuleAssemblyRuntimeBoundaryTest" --rerun-tasks --no-daemon --console=plain`

Expected: FAIL listing current `ITianshuConfig` consumers and the `config` record component.

- [ ] **Step 3: Commit the RED tests**

```bash
git add tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleConfigurationBoundaryTest.java tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleAssemblyRuntimeBoundaryTest.java
git commit -m "test: lock module configuration boundaries"
```

### Task 2: Introduce bounded read-only configuration ports

**Files:**
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/settings/AsrConfiguration.java`
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/settings/LlmConfiguration.java`
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/settings/TtsConfiguration.java`
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/storage/AXStorageConfiguration.java`
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/voice/VoiceResourceConfiguration.java`
- Test: `tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleConfigurationPortTest.java`

**Interfaces:**
- Produces: `AsrConfiguration`, `LlmConfiguration`, `TtsConfiguration`, `AXStorageConfiguration`, and `VoiceResourceConfiguration`.
- Consumes: `TriggerMode`, `Path`, and primitive runtime values only.

- [ ] **Step 1: Write failing reflection tests for read-only ports**

```java
@Test
void configurationPortsExposeNoMutationOrIoOperations() {
    for (Class<?> port : List.of(
            AsrConfiguration.class,
            LlmConfiguration.class,
            TtsConfiguration.class,
            AXStorageConfiguration.class,
            VoiceResourceConfiguration.class
    )) {
        assertTrue(port.isInterface());
        assertTrue(Arrays.stream(port.getMethods()).noneMatch(method ->
                method.getName().startsWith("set")
                        || method.getName().equals("save")
                        || method.getName().startsWith("find")
                        || method.getName().startsWith("scan")));
    }
}
```

- [ ] **Step 2: Run the port test and record RED**

Run: `./gradlew :tianshu-common:test --tests "*ModuleConfigurationPortTest" --rerun-tasks --no-daemon --console=plain`

Expected: compilation failure because the port types do not exist.

- [ ] **Step 3: Add the interfaces with existing runtime defaults**

```java
public interface AsrConfiguration {
    boolean enabled();
    TriggerMode triggerMode();
    String selectedMicrophoneName();
    boolean rnnoiseEnabled();
    boolean highPassFilterEnabled();
    boolean vadEnabled();
    String selectedModelName();
    Path moduleRoot();
}
```

```java
public interface TtsConfiguration {
    boolean enabled();
    String selectedModelName();
    Path moduleRoot();
    Path voiceLibraryRoot();
}
```

`LlmConfiguration` contains the current read values used by LLM only: enabled, selected chat/embedding model identifiers, GPU layer/device, frame guard, MTP, context/token budgets, cache types, queue/admission values, request timeout, RAG roots and module root. Values currently supplied only by `ITianshuConfig` defaults are copied as explicit read-only defaults with the same exact numeric/string values.

```java
public interface AXStorageConfiguration {
    Path storageRoot();
}

public interface VoiceResourceConfiguration {
    Path asrModuleRoot();
}
```

- [ ] **Step 4: Run the port test and record GREEN**

Run: `./gradlew :tianshu-common:test --tests "*ModuleConfigurationPortTest" --rerun-tasks --no-daemon --console=plain`

Expected: PASS.

- [ ] **Step 5: Commit the ports**

```bash
git add tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/settings/AsrConfiguration.java tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/settings/LlmConfiguration.java tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/settings/TtsConfiguration.java tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/storage/AXStorageConfiguration.java tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/voice/VoiceResourceConfiguration.java tianshu-common/src/test/java/com/rheinmetal/tianshu/architecture/ModuleConfigurationPortTest.java
git commit -m "refactor: add bounded module configuration ports"
```

### Task 3: Move LLM model discovery out of configuration

**Files:**
- Create: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/model/LlmModelPathResolver.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmEngineProvider.java`
- Test: `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm/model/LlmModelPathResolverTest.java`

**Interfaces:**
- Consumes: `LlmConfiguration.moduleRoot()`, selected model identifiers, and existing `LlmModelManager` catalog metadata.
- Produces: `Path resolveChatModel()`, `Path resolveEmbeddingModel()`, and `Path resolveMtpDraftModel()`.

- [ ] **Step 1: Write resolver characterization tests**

```java
@Test
void catalogFileWinsOverOtherGgufFiles() throws Exception {
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("catalog.gguf"), "catalog");
    Files.writeString(modelDir.resolve("aaa.gguf"), "other");
    assertEquals(modelDir.resolve("catalog.gguf"), resolver.resolveChatModel());
}

@Test
void deterministicFirstGgufIsUsedWhenCatalogFileIsAbsent() throws Exception {
    Files.createDirectories(modelDir);
    Files.writeString(modelDir.resolve("b.gguf"), "b");
    Files.writeString(modelDir.resolve("A.gguf"), "a");
    assertEquals(modelDir.resolve("A.gguf"), resolver.resolveChatModel());
}

@Test
void blankSelectionDoesNotGuessAFile() {
    assertNull(blankResolver.resolveChatModel());
}
```

- [ ] **Step 2: Run resolver tests and record RED**

Run: `./gradlew :tianshu-common:test --tests "*LlmModelPathResolverTest" --rerun-tasks --no-daemon --console=plain`

Expected: compilation failure because `LlmModelPathResolver` does not exist.

- [ ] **Step 3: Implement deterministic resolution**

```java
public Path resolveChatModel() {
    String selection = normalized(configuration.selectedModelName());
    if (selection.isEmpty()) return null;
    Path modelRoot = configuration.moduleRoot().resolve("model").normalize();
    Path selected = modelRoot.resolve(selection).normalize();
    requireWithin(modelRoot, selected);
    if (selection.toLowerCase(Locale.ROOT).endsWith(".gguf")) return selected;
    return resolveCatalogOrFirstGguf(selected, LlmModelManager.getModelByName(selection));
}
```

The embedding method uses `getEmbeddingModelByName`; both methods preserve the current case-insensitive deterministic filename sort. IO failures retain a cause in a domain exception or structured resolution result instead of being swallowed in a configuration default method.

- [ ] **Step 4: Inject the resolver into `LlmEngineProvider`**

Replace `getLlmGgufFilePath()`, `getLlmEmbeddingGgufFilePath()`, and draft path calls with the resolver. Do not change engine builder values or GPU execution settings.

- [ ] **Step 5: Run LLM tests**

Run: `./gradlew :tianshu-common:test --tests "com.rheinmetal.tianshu.function.llm.*" --rerun-tasks --no-daemon --console=plain`

Expected: PASS.

- [ ] **Step 6: Commit the resolver**

```bash
git add tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/model/LlmModelPathResolver.java tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmEngineProvider.java tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm/model/LlmModelPathResolverTest.java
git commit -m "refactor: move llm model discovery into model domain"
```

### Task 4: Migrate ASR, AX, and LLM to their ports

**Files:**
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModelService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModule.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/AsrModuleInstaller.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/audio/AsrAudioPipelineFactory.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/control/AsrController.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/engine/AsrEngineBootstrap.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr/settings/AsrSettingsSnapshot.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/AXModule.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/AXModuleInstaller.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/core/context/AXMemoryWindowPolicy.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium/storage/AXStorageLayout.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmEngineProvider.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmModelService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmModule.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmModuleInstaller.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmModuleService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/LlmTaskAdmissionController.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/rag/LlmRagCacheLayout.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/rag/LlmRagPathResolver.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/service/LLMService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/service/LlmInferenceDefaults.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/service/LlmInferenceGovernor.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm/service/LlmServiceMetadata.java`
- Modify: corresponding test fakes under `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/{asr,llm,auxilium}`

**Interfaces:**
- Consumes: ports from Task 2 and resolver from Task 3.
- Produces: ASR/AX/LLM constructors with no aggregate config dependency.

- [ ] **Step 1: Migrate ASR constructors and getters mechanically**

Use `AsrConfiguration` in `AsrModule`, installer, model service, controller, audio pipeline, engine bootstrap, and settings snapshot. Derive model root as `configuration.moduleRoot().resolve("model")`; derive the selected model path from the normalized model identifier in ASR domain code.

- [ ] **Step 2: Run ASR tests**

Run: `./gradlew :tianshu-common:test --tests "com.rheinmetal.tianshu.function.asr.*" --rerun-tasks --no-daemon --console=plain`

Expected: PASS with no behavior changes.

- [ ] **Step 3: Migrate AX storage and internal memory policy**

Change `AXStorageLayout` to accept `AXStorageConfiguration` and use `storageRoot()` directly. Replace `AXMemoryWindowPolicy.fromConfig(ITianshuConfig)` with explicit `AXMemoryWindowPolicy` injection; the production assembler supplies `AXMemoryWindowPolicy.DEFAULT` because current NeoForge does not override those values.

- [ ] **Step 4: Run AX tests**

Run: `./gradlew :tianshu-common:test --tests "com.rheinmetal.tianshu.function.auxilium.*" --rerun-tasks --no-daemon --console=plain`

Expected: PASS.

- [ ] **Step 5: Migrate LLM constructors and policies**

Use `LlmConfiguration` in LLM module, model service, engine provider, module service, admission controller, RAG layouts, inference defaults/governor, service metadata, and tests. No AX class may import `LlmConfiguration`; AX receives its own memory policy.

- [ ] **Step 6: Run LLM tests and the architecture tests**

Run: `./gradlew :tianshu-common:test --tests "com.rheinmetal.tianshu.function.llm.*" --tests "*ModuleConfigurationBoundaryTest" --rerun-tasks --no-daemon --console=plain`

Expected: LLM tests pass; the boundary test remains RED only for TTS until Task 5.

- [ ] **Step 7: Commit the module migrations**

```bash
git add tianshu-common/src/main/java/com/rheinmetal/tianshu/function/asr tianshu-common/src/main/java/com/rheinmetal/tianshu/function/auxilium tianshu-common/src/main/java/com/rheinmetal/tianshu/function/llm tianshu-common/src/test/java/com/rheinmetal/tianshu/function/asr tianshu-common/src/test/java/com/rheinmetal/tianshu/function/auxilium tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm
git commit -m "refactor: isolate asr ax and llm configuration"
```

### Task 5: Migrate TTS without letting preview mutate persistent selection

**Files:**
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModelService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModule.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsModuleInstaller.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/TtsVoiceLibraryService.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/settings/TtsSettingsSnapshot.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/MossTtsBackend.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/SherpaOnnxTtsBackend.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/SherpaOnnxTtsConfigFactory.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/TtsEngineProvider.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/TtsModelResolver.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/synthesis/DefaultTtsSynthesisEngine.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts/voice/TtsVoiceCloneRegistry.java`
- Test: `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/tts/synthesis/DefaultTtsSynthesisEngineSelectionTest.java`

**Interfaces:**
- Consumes: `TtsConfiguration`.
- Produces: active model selection owned by the synthesis engine; persistent selection remains owned by NeoForge GUI configuration.

- [ ] **Step 1: Write a failing preview-selection test**

```java
@Test
void temporaryModelActivationDoesNotChangeConfiguredSelection() {
    TestTtsConfiguration configuration = new TestTtsConfiguration("configured-model", root);
    DefaultTtsSynthesisEngine engine = createEngine(configuration, catalogWith("configured-model", "preview-model"));
    assertTrue(engine.useModel("preview-model"));
    assertEquals("configured-model", configuration.selectedModelName());
}
```

- [ ] **Step 2: Run the selection test and record RED**

Run: `./gradlew :tianshu-common:test --tests "*DefaultTtsSynthesisEngineSelectionTest" --rerun-tasks --no-daemon --console=plain`

Expected: FAIL because current `TtsModelService.useModel` mutates the configuration.

- [ ] **Step 3: Separate configured and active model resolution**

Add `TtsModelResolver.resolve(String modelName)` and keep `resolveCurrent()` as `resolve(configuration.selectedModelName())`. Store `activeModelName` inside `DefaultTtsSynthesisEngine`; `useModel` changes only that field, and `clearModel` clears active runtime state. Remove `TtsModelService.useModel` and `clearModel` configuration mutations.

- [ ] **Step 4: Migrate all TTS collaborators to `TtsConfiguration`**

Update module, installer, model service, settings snapshot, engine provider, Sherpa/MOSS backends, voice library and clone registry. Keep all synthesis parameters and MOSS paths unchanged.

- [ ] **Step 5: Run TTS tests**

Run: `./gradlew :tianshu-common:test --tests "com.rheinmetal.tianshu.function.tts.*" --rerun-tasks --no-daemon --console=plain`

Expected: PASS, including model preview switch/restore and cancellation tests.

- [ ] **Step 6: Commit the TTS migration**

```bash
git add tianshu-common/src/main/java/com/rheinmetal/tianshu/function/tts tianshu-common/src/test/java/com/rheinmetal/tianshu/function/tts
git commit -m "refactor: isolate tts configuration and active model state"
```

### Task 6: Remove the aggregate from Core and wire NeoForge ports

**Files:**
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/core/TianshuCoreManager.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/core/lifecycle/TianshuModuleAssemblyContext.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/protocol/voice/VoiceResourceManager.java`
- Modify: `tianshu-common/src/main/java/com/rheinmetal/tianshu/function/TianshuCoreModuleInstallers.java`
- Delete: `tianshu-common/src/main/java/com/rheinmetal/tianshu/api/ITianshuConfig.java`
- Modify: `tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/config/ClientConfig.java`
- Modify: `tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/TianshuClient.java`
- Modify: `tianshu-neoforge/src/main/java/com/rheinmetal/tianshu/client/lifecycle/ClientTianshuModuleAssembler.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/core/TianshuCoreManagerLifecycleTest.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/core/TianshuCoreManagerReadinessTest.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm/TestLlmSupport.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm/service/LLMServiceTest.java`
- Modify: `tianshu-common/src/test/java/com/rheinmetal/tianshu/function/llm/service/LLMServiceRealModelSmokeTest.java`

**Interfaces:**
- Consumes: all Task 2 ports.
- Produces: Core constructor requiring only `VoiceResourceConfiguration`; NeoForge composition root distributes module ports.

- [ ] **Step 1: Make `ClientConfig` implement the bounded ports**

Map each new read method to the existing `ModConfigSpec` value without changing keys or defaults. Keep GUI setters and `save()` as concrete NeoForge methods, not interface methods. Return module roots from the same existing `config/Tianshu/module/{asr,llm,tts}` layout.

- [ ] **Step 2: Remove configuration from assembly context**

```java
public record TianshuModuleAssemblyContext(
        IGameEnvironment env,
        IAudioBridge audioBridge,
        ModuleRuntimeAccess moduleRuntime,
        BooleanSupplier voiceInputGate,
        LongSupplier interruptionSignal
) {}
```

`TianshuClient` captures its concrete `ClientConfig` in the assembler factory closure and passes it to `ClientTianshuModuleAssembler`; it no longer reads `context.config()`.

- [ ] **Step 3: Narrow Core to voice-resource configuration**

Change `TianshuCoreManager` to receive `VoiceResourceConfiguration` for `VoiceResourceManager`. Core must not store ASR/LLM/TTS/AX configuration. Update Core tests with a voice-resource fake rooted in `@TempDir`.

- [ ] **Step 4: Delete `ITianshuConfig` and migrate test fakes**

Replace aggregate fake configs with the smallest module port required by each test. Remove no-op setters and default filesystem discovery from tests as well as production.

- [ ] **Step 5: Run architecture, common, and NeoForge compile checks**

Run: `./gradlew :tianshu-common:test --tests "*ModuleConfigurationBoundaryTest" --tests "*ModuleConfigurationPortTest" :tianshu-neoforge:compileJava --rerun-tasks --no-daemon --console=plain`

Expected: PASS; `rg -n "ITianshuConfig|context\.config\(\)" tianshu-common/src tianshu-neoforge/src` returns no matches.

- [ ] **Step 6: Commit aggregate removal and wiring**

```bash
git add tianshu-common/src tianshu-neoforge/src
git commit -m "refactor: remove aggregate tianshu configuration"
```

### Task 7: Synchronize documentation and run full verification

**Files:**
- Modify: `libs/common-cleanup-audit.md`
- Modify: `libs/module-config-port-design.md` only if implementation names differ from the approved design.
- Modify: Core, ASR, AX, LLM, and TTS architecture/integration documents that mention `ITianshuConfig` or assembly configuration.

**Interfaces:**
- Consumes: final implementation.
- Produces: accurate module ownership and integration documentation.

- [ ] **Step 1: Update documentation**

Record the exact port names, Core composition ownership, LLM model resolver behavior, TTS configured-versus-active model distinction, and the absence of compatibility accessors. Do not change external protocol usage documents unless their public behavior changed.

- [ ] **Step 2: Run static scans**

Run: `rg -n "ITianshuConfig|TianshuModuleAssemblyContext\.config|context\.config\(\)" tianshu-common tianshu-neoforge libs`

Expected: no production or current-document references; historical audit prose may mention the removed symbol only when explicitly marked completed.

- [ ] **Step 3: Run full forced verification**

Run: `./gradlew :tianshu-common:test :tianshu-neoforge:compileJava --rerun-tasks --no-daemon --console=plain`

Expected: `BUILD SUCCESSFUL`; all non-opt-in tests pass, and only the known real-device/model smoke tests remain skipped.

- [ ] **Step 4: Check diffs and record counts**

Run: `git diff --check` and `git diff --cached --check`.

Expected: no whitespace errors. Count JUnit XML totals and write tests/failures/errors/skipped into the audit ledger.

- [ ] **Step 5: Commit documentation**

```bash
git add libs/common-cleanup-audit.md libs/module-config-port-design.md tianshu-common/doc
git commit -m "docs: document module configuration boundaries"
```
