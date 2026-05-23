package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.PotionEffectData;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public final class ActiveEffectsRuntimeFactProvider extends AbstractDirtyRuntimeFactProvider {
    private final WorldStateProvider worldStateProvider;

    public ActiveEffectsRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.active_effects";
    }

    @Override
    protected String snapshotSignature(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getEnvironment() == null) {
            return "active_effects:empty";
        }
        List<PotionEffectData> effects = worldStateProvider.getEnvironment().getActivePotionEffects();
        if (effects == null || effects.isEmpty()) {
            return "active_effects:empty";
        }
        return effects.stream()
                .filter(effect -> effect != null && effect.getDisplayName() != null && !effect.getDisplayName().isBlank())
                .sorted(Comparator.comparing(PotionEffectData::isBeneficial).thenComparing(PotionEffectData::getDisplayName))
                .map(this::encode)
                .filter(encoded -> !encoded.isBlank())
                .collect(Collectors.joining("|"));
    }

    @Override
    protected List<RuntimeFact> collectFacts(AXScope scope, AXRequest request) {
        if (worldStateProvider == null || worldStateProvider.getEnvironment() == null) {
            return List.of();
        }
        List<PotionEffectData> effects = worldStateProvider.getEnvironment().getActivePotionEffects();
        if (effects == null || effects.isEmpty()) {
            return List.of();
        }
        String value = effects.stream()
                .filter(effect -> effect != null && effect.getDisplayName() != null && !effect.getDisplayName().isBlank())
                .sorted(Comparator.comparing(PotionEffectData::isBeneficial).thenComparing(PotionEffectData::getDisplayName))
                .map(this::encode)
                .filter(encoded -> !encoded.isBlank())
                .collect(Collectors.joining("|"));
        if (value.isBlank()) {
            return List.of();
        }
        long now = System.currentTimeMillis();
        return List.of(new RuntimeFact(
                "fact.player.active_effects.current",
                "active_effects",
                providerId(),
                "player",
                Map.of("effects", value),
                List.of("player", "effects"),
                76,
                now,
                120_000L,
                now
        ));
    }

    private String encode(PotionEffectData effect) {
        return safe(effect.getDisplayName()) + ";" + effect.getAmplifier() + ";" + Math.max(0, effect.getDurationTicks());
    }

    private String safe(String value) {
        return value == null ? "" : value.trim().replace(";", " ").replace("|", " ");
    }
}
