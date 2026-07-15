package com.rheinmetal.tianshu.function.tts.voice;

import com.rheinmetal.tianshu.api.IGameEnvironment;
import com.rheinmetal.tianshu.function.tts.settings.TtsConfiguration;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlAction;
import com.rheinmetal.tianshu.function.tts.runtime.TtsControlResult;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailure;
import com.rheinmetal.tianshu.function.tts.runtime.TtsFailureCode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class TtsVoiceCloneRegistry {
    private static final int MAX_IMPORTED_AUDIO_BYTES = 20 * 1024 * 1024;

    private final IGameEnvironment env;
    private final TtsConfiguration config;
    private final TtsReferenceAudioLoader referenceAudioLoader = new TtsReferenceAudioLoader();
    private final Map<String, TtsVoiceCloneProfile> profiles = new ConcurrentHashMap<>();

    public TtsVoiceCloneRegistry(IGameEnvironment env, TtsConfiguration config) {
        this.env = env;
        this.config = config;
    }

    public TtsControlResult load(String voiceId, String ownerModuleId, String sampleNameOrPath, String referenceText) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice id is empty");
        }
        Path samplePath = resolveSamplePath(sampleNameOrPath);
        if (samplePath == null) {
            return reject(TtsControlAction.LOAD_VOICE, "TTS voice sample is not available: " + normalize(sampleNameOrPath));
        }
        try {
            BasicFileAttributes attributes = Files.readAttributes(samplePath, BasicFileAttributes.class);
            TtsReferenceAudio referenceAudio = referenceAudioLoader.loadMono(samplePath);
            if (referenceAudio.samples().length == 0) {
                return TtsControlResult.rejected(
                        TtsControlAction.LOAD_VOICE,
                        TtsFailure.of(TtsFailureCode.VOICE_CLONE_UNAVAILABLE, "TTS voice sample is empty: " + samplePath.getFileName())
                );
            }
            TtsVoiceCloneProfile profile = new TtsVoiceCloneProfile(
                    normalizedVoiceId,
                    normalizeOwner(ownerModuleId),
                    samplePath,
                    referenceAudio,
                    referenceText,
                    attributes.lastModifiedTime().toMillis(),
                    attributes.size(),
                    System.currentTimeMillis()
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
            return TtsControlResult.accepted(TtsControlAction.UNLOAD_VOICE, 0);
        }
        String owner = normalizeOwner(ownerModuleId);
        if (!owner.isBlank() && !existing.ownerModuleId().isBlank() && !owner.equals(existing.ownerModuleId())) {
            return reject(TtsControlAction.UNLOAD_VOICE, "TTS voice belongs to another module: " + normalizedVoiceId);
        }
        return profiles.remove(normalizedVoiceId, existing)
                ? TtsControlResult.accepted(TtsControlAction.UNLOAD_VOICE, 1)
                : TtsControlResult.accepted(TtsControlAction.UNLOAD_VOICE, 0);
    }

    public TtsControlResult clear(String ownerModuleId) {
        String owner = normalizeOwner(ownerModuleId);
        Predicate<TtsVoiceCloneProfile> shouldRemove = profile -> owner.isBlank() || owner.equals(profile.ownerModuleId());
        int before = profiles.size();
        profiles.entrySet().removeIf(entry -> shouldRemove.test(entry.getValue()));
        return TtsControlResult.accepted(TtsControlAction.CLEAR_VOICE_CACHE, Math.max(0, before - profiles.size()));
    }

    public TtsControlResult importVoice(
            String voiceId,
            String ownerModuleId,
            byte[] audio,
            String referenceText
    ) {
        String normalizedVoiceId = normalizeVoiceId(voiceId);
        if (normalizedVoiceId.isBlank()) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice id is empty");
        }
        if (audio == null || audio.length == 0) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice audio is empty");
        }
        if (audio.length > MAX_IMPORTED_AUDIO_BYTES) {
            return reject(TtsControlAction.IMPORT_VOICE, "TTS voice audio is too large: " + audio.length);
        }
        String safeFileName = importedAudioFileName(normalizedVoiceId, audio);
        try {
            Path ownerDir = ownerVoiceDirectory(ownerModuleId);
            Files.createDirectories(ownerDir);
            Path target = uniqueTarget(ownerDir, safeFileName);
            Files.write(target, audio, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            TtsControlResult loadResult = load(normalizedVoiceId, ownerModuleId, target.toString(), referenceText);
            if (!loadResult.accepted()) {
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

    public int size() {
        return profiles.size();
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

    private static String safeAudioFileName(String value) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            return "";
        }
        Path fileName = Path.of(normalized).getFileName();
        if (fileName == null) {
            return "";
        }
        String safe = safePathSegment(fileName.toString());
        String lower = safe.toLowerCase();
        if (!(lower.endsWith(".wav") || lower.endsWith(".mp3") || lower.endsWith(".flac"))) {
            return "";
        }
        return safe;
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
