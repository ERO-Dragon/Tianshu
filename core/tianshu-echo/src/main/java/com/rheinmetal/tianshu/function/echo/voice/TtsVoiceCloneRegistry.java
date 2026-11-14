package com.rheinmetal.tianshu.function.echo.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import com.rheinmetal.tianshu.function.echo.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.echo.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.echo.runtime.TtsFailureCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class TtsVoiceCloneRegistry {
    private static final int MAX_IMPORTED_AUDIO_BYTES = 20 * 1024 * 1024;

    private final IGameEnvironment env;
    private final EchoConfiguration config;
    private final TtsReferenceAudioLoader referenceAudioLoader = new TtsReferenceAudioLoader();
    private final Map<String, TtsVoiceCloneProfile> profiles = new ConcurrentHashMap<>();

    public TtsVoiceCloneRegistry(IGameEnvironment env, EchoConfiguration config) {
        this.env = env;
        this.config = config;
    }

    public synchronized TtsControlResult load(String voiceId, String ownerModuleId, String sampleNameOrPath, String referenceText) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice id is empty");
        }
        String owner = normalizeOwner(ownerModuleId);
        if (owner.isBlank()) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice owner is empty");
        }
        TtsVoiceCloneProfile existing = profiles.get(normalizedVoiceId);
        if (existing != null && !owner.equals(existing.ownerModuleId())) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice belongs to another module: " + normalizedVoiceId);
        }
        Path samplePath = resolveSamplePath(sampleNameOrPath);
        if (samplePath == null) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice sample is not available: " + normalize(sampleNameOrPath));
        }
        try {
            TtsReferenceAudio referenceAudio = referenceAudioLoader.loadMono(samplePath);
            if (referenceAudio.samples().length == 0) {
                return TtsControlResult.rejected(
                        TtsControlAction.LOAD_VOICE,
                        TtsFailure.of(TtsFailureCode.VOICE_CLONE_UNAVAILABLE, "TTS voice sample is empty: " + samplePath.getFileName())
                );
            }
            TtsVoiceCloneProfile profile = new TtsVoiceCloneProfile(
                    normalizedVoiceId,
                    owner,
                    samplePath,
                    referenceAudio,
                    referenceText
            );
            profiles.put(normalizedVoiceId, profile);
            return TtsControlResult.accepted(TtsControlAction.LOAD_VOICE, 1);
        } catch (Exception exception) {
            if (env != null) {
                env.error("TTS voice clone load failed: " + normalizedVoiceId, exception);
            }
            return TtsControlResult.rejected(
                    TtsControlAction.LOAD_VOICE,
                    TtsFailure.fromThrowable(TtsFailureCode.VOICE_CLONE_UNAVAILABLE, exception)
            );
        }
    }

    public TtsControlResult unload(String voiceId, String ownerModuleId) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return reject(TtsControlAction.UNLOAD_VOICE, "TTS voice id is empty");
        }
        TtsVoiceCloneProfile existing = profiles.get(normalizedVoiceId);
        if (existing == null) {
            return TtsControlResult.rejected(TtsControlAction.UNLOAD_VOICE, TtsFailure.of(
                    TtsFailureCode.REQUEST_NOT_FOUND,
                    "TTS voice is not registered: " + normalizedVoiceId
            ));
        }
        String owner = normalizeOwner(ownerModuleId);
        if (!owner.isBlank() && !existing.ownerModuleId().isBlank() && !owner.equals(existing.ownerModuleId())) {
            return reject(TtsControlAction.UNLOAD_VOICE, "TTS voice belongs to another module: " + normalizedVoiceId);
        }
        if (!profiles.remove(normalizedVoiceId, existing)) {
            return TtsControlResult.rejected(TtsControlAction.UNLOAD_VOICE, TtsFailure.of(
                    TtsFailureCode.REQUEST_NOT_FOUND,
                    "TTS voice is not registered: " + normalizedVoiceId
            ));
        }
        releaseSample(existing);
        return TtsControlResult.accepted(TtsControlAction.UNLOAD_VOICE, 1);
    }

    public TtsControlResult clear(String ownerModuleId) {
        String owner = normalizeOwner(ownerModuleId);
        Predicate<TtsVoiceCloneProfile> shouldRemove = profile -> owner.isBlank() || owner.equals(profile.ownerModuleId());
        Map<String, TtsVoiceCloneProfile> removed = new LinkedHashMap<>();
        profiles.entrySet().removeIf(entry -> {
            if (!shouldRemove.test(entry.getValue())) {
                return false;
            }
            removed.put(entry.getKey(), entry.getValue());
            return true;
        });
        removed.values().forEach(this::releaseSample);
        return TtsControlResult.accepted(TtsControlAction.CLEAR_VOICE_CACHE, removed.size());
    }

    /**
     * Drops the audio file an import created. A profile can point at a sample the caller placed in the
     * library itself, so only files this owner's import directory holds are deleted, and only once no
     * other profile still references them.
     */
    private void releaseSample(TtsVoiceCloneProfile profile) {
        if (profile == null || profile.samplePath() == null || config == null) {
            return;
        }
        Path sample = profile.samplePath().toAbsolutePath().normalize();
        Path ownerDirectory = ownerVoiceDirectory(profile.ownerModuleId());
        if (!sample.startsWith(ownerDirectory)) {
            return;
        }
        boolean referenced = profiles.values().stream().anyMatch(other -> other.samplePath() != null
                && other.samplePath().toAbsolutePath().normalize().equals(sample));
        if (referenced) {
            return;
        }
        try {
            Files.deleteIfExists(sample);
        } catch (Exception exception) {
            if (env != null) {
                env.warn("TTS voice sample could not be deleted: " + sample);
            }
        }
    }

    public synchronized TtsControlResult importVoice(
            String voiceId,
            String ownerModuleId,
            byte[] audio,
            String referenceText
    ) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice id is empty");
        }
        String owner = normalizeOwner(ownerModuleId);
        if (owner.isBlank()) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice owner is empty");
        }
        TtsVoiceCloneProfile existing = profiles.get(normalizedVoiceId);
        if (existing != null && !owner.equals(existing.ownerModuleId())) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice belongs to another module: " + normalizedVoiceId);
        }
        if (audio == null || audio.length == 0) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice audio is empty");
        }
        if (audio.length > MAX_IMPORTED_AUDIO_BYTES) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice audio is too large: " + audio.length);
        }
        String safeFileName = importedAudioFileName(normalizedVoiceId, audio);
        try {
            Path ownerDir = ownerVoiceDirectory(owner);
            Files.createDirectories(ownerDir);
            Path target = importTarget(ownerDir, existing, safeFileName);
            writeImportedAudio(target, audio);
            TtsControlResult loadResult = load(normalizedVoiceId, owner, target.toString(), referenceText);
            if (!loadResult.accepted()) {
                Files.deleteIfExists(target);
                return TtsControlResult.rejected(TtsControlAction.IMPORT_VOICE, loadResult.failure());
            }
            return TtsControlResult.accepted(TtsControlAction.IMPORT_VOICE, 1);
        } catch (Exception exception) {
            if (env != null) {
                env.error("TTS voice import failed: " + normalizedVoiceId, exception);
            }
            return TtsControlResult.rejected(
                    TtsControlAction.IMPORT_VOICE,
                    TtsFailure.fromThrowable(TtsFailureCode.VOICE_CLONE_UNAVAILABLE, exception)
            );
        }
    }

    /**
     * Re-importing a voice replaces its file instead of adding another copy: a caller that re-sends the
     * same audio must not accumulate one file per attempt.
     */
    private Path importTarget(Path ownerDir, TtsVoiceCloneProfile existing, String fileName) {
        if (existing != null && existing.samplePath() != null) {
            Path previous = existing.samplePath().toAbsolutePath().normalize();
            if (previous.startsWith(ownerDir) && Files.isRegularFile(previous)) {
                return previous;
            }
        }
        return uniqueTarget(ownerDir, fileName);
    }

    private static void writeImportedAudio(Path target, byte[] audio) throws java.io.IOException {
        try {
            Files.write(target, audio, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE);
        } catch (Exception exception) {
            // A failed import must not leave a half-written file behind.
            try {
                Files.deleteIfExists(target);
            } catch (Exception ignored) {
                // The write failure is the one worth reporting.
            }
            throw exception;
        }
    }

    public Optional<TtsVoiceCloneProfile> resolve(String voiceId) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return Optional.empty();
        }
        TtsVoiceCloneProfile profile = profiles.get(normalizedVoiceId);
        if (profile == null || !Files.isRegularFile(profile.samplePath())) {
            if (profile != null) {
                profiles.remove(normalizedVoiceId, profile);
            }
            return Optional.empty();
        }
        return Optional.of(profile);
    }

    public static int maxImportedAudioBytes() {
        return MAX_IMPORTED_AUDIO_BYTES;
    }

    private Path resolveSamplePath(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank() || config == null) {
            return null;
        }
        Path voiceRoot = config.getVoiceLibraryPath().toAbsolutePath().normalize();
        Path fileName = Path.of(normalized).getFileName();
        Path fromLibrary = fileName == null ? null : voiceRoot.resolve(fileName.toString()).normalize();
        if (fromLibrary != null && fromLibrary.startsWith(voiceRoot) && Files.isRegularFile(fromLibrary)) {
            return fromLibrary;
        }
        Path explicit = Path.of(normalized).toAbsolutePath().normalize();
        if (explicit.startsWith(voiceRoot) && Files.isRegularFile(explicit)) {
            return explicit;
        }
        return null;
    }

    private static String importedAudioFileName(String voiceId, byte[] audio) {
        String base = safePathSegment(voiceId);
        if (base.isBlank()) {
            base = "voice";
        }
        return base + audioExtension(audio);
    }

    private Path ownerVoiceDirectory(String ownerModuleId) {
        Path voiceRoot = config.getVoiceLibraryPath().toAbsolutePath().normalize();
        String owner = safePathSegment(normalizeOwner(ownerModuleId));
        if (owner.isBlank()) {
            owner = "unknown";
        }
        return voiceRoot.resolve(owner).normalize();
    }

    private Path uniqueTarget(Path dir, String fileName) {
        Path target = dir.resolve(fileName).normalize();
        if (!target.startsWith(dir.normalize())) {
            target = dir.resolve(System.currentTimeMillis() + ".wav").normalize();
        }
        if (!Files.exists(target)) {
            return target;
        }
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext = fileName.substring(dot);
        }
        for (int index = 1; index < 1000; index++) {
            Path candidate = dir.resolve(base + "-" + index + ext).normalize();
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
        return dir.resolve(System.currentTimeMillis() + "-" + fileName).normalize();
    }

    private static String audioExtension(byte[] audio) {
        if (audio.length >= 12
                && audio[0] == 'R' && audio[1] == 'I' && audio[2] == 'F' && audio[3] == 'F'
                && audio[8] == 'W' && audio[9] == 'A' && audio[10] == 'V' && audio[11] == 'E') {
            return ".wav";
        }
        if (audio.length >= 4
                && audio[0] == 'f' && audio[1] == 'L' && audio[2] == 'a' && audio[3] == 'C') {
            return ".flac";
        }
        if (audio.length >= 3
                && audio[0] == 'I' && audio[1] == 'D' && audio[2] == '3') {
            return ".mp3";
        }
        if (audio.length >= 2 && (audio[0] & 0xFF) == 0xFF && (audio[1] & 0xE0) == 0xE0) {
            return ".mp3";
        }
        return ".wav";
    }

    private static String safePathSegment(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        String safe = normalized.replaceAll("[^A-Za-z0-9._-]", "_");
        while (safe.contains("..")) {
            safe = safe.replace("..", "_");
        }
        if (safe.length() > 128) {
            int dot = safe.lastIndexOf('.');
            if (dot > 0 && dot < safe.length() - 1) {
                String ext = safe.substring(dot);
                int baseLength = Math.max(1, 128 - ext.length());
                safe = safe.substring(0, Math.min(baseLength, dot)) + ext;
            } else {
                safe = safe.substring(0, 128);
            }
        }
        return safe;
    }

    private TtsControlResult reject(TtsControlAction action, String message) {
        return TtsControlResult.rejected(action, TtsFailure.of(TtsFailureCode.INVALID_REQUEST, message));
    }

    private static String normalizeVoiceId(String value) {
        String normalized = normalize(value);
        if (normalized.length() > 128) {
            return normalized.substring(0, 128);
        }
        return normalized;
    }

    private static String normalizeOwner(String value) {
        String normalized = normalize(value);
        if (normalized.length() > 128) {
            return normalized.substring(0, 128);
        }
        return normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
