package com.rheinmetal.tianshu.function.assistant.fact;

import com.rheinmetal.tianshu.function.assistant.AssistantRequest;
import com.rheinmetal.tianshu.function.assistant.scope.AssistantScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.NavigationInfo;
import com.rheinmetal.tianshu.snapshot.PlayerStatusData;
import com.rheinmetal.tianshu.snapshot.PositionData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BasicWorldStateRuntimeFactProvider implements RuntimeFactProvider {
    private final WorldStateProvider worldStateProvider;

    public BasicWorldStateRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.basic_world_state";
    }

    @Override
    public List<RuntimeFact> refreshForQuestion(AssistantScope scope, AssistantRequest request) {
        if (worldStateProvider == null) {
            return List.of();
        }
        List<RuntimeFact> facts = new ArrayList<>();
        appendDimension(facts);
        appendPlayerStatus(facts);
        appendEnvironment(facts);
        return facts;
    }

    private void appendDimension(List<RuntimeFact> facts) {
        if (worldStateProvider.getPlayerState() == null) {
            return;
        }
        NavigationInfo navigation = worldStateProvider.getPlayerState().getPlayerNavigationInfo();
        PositionData position = navigation == null ? null : navigation.getCurrent();
        if (position == null) {
            return;
        }
        facts.add(new RuntimeFact(
                "fact.player.dimension.current",
                "player_dimension",
                providerId(),
                "player",
                Map.of(
                        "dimension", safe(position.getDimension()),
                        "dimensionDisplayName", safe(worldStateProvider.getPlayerState().getCurrentDimensionDisplayName())
                ),
                List.of("player", "dimension"),
                82,
                System.currentTimeMillis(),
                120_000L,
                System.currentTimeMillis()
        ));
    }

    private void appendPlayerStatus(List<RuntimeFact> facts) {
        if (worldStateProvider.getPlayerState() == null) {
            return;
        }
        PlayerStatusData status = worldStateProvider.getPlayerState().getPlayerStatus();
        if (status == null) {
            return;
        }
        facts.add(new RuntimeFact(
                "fact.player.status.current",
                "player_status",
                providerId(),
                "player",
                Map.of(
                        "health", String.format("%.1f/%.1f", status.getHealth(), status.getMaxHealth()),
                        "hunger", Integer.toString(status.getHunger()),
                        "saturation", String.format("%.1f", status.getSaturation()),
                        "experienceLevel", Integer.toString(status.getExperienceLevel())
                ),
                List.of("player", "status"),
                80,
                System.currentTimeMillis(),
                120_000L,
                System.currentTimeMillis()
        ));
    }

    private void appendEnvironment(List<RuntimeFact> facts) {
        if (worldStateProvider.getEnvironment() == null) {
            return;
        }
        WorldEnvironmentData environment = worldStateProvider.getEnvironment().getWorldEnvironmentInfo();
        if (environment == null) {
            return;
        }
        facts.add(new RuntimeFact(
                "fact.world.environment.current",
                "world_environment",
                providerId(),
                "world",
                Map.of(
                        "biome", safe(environment.getBiomeId()),
                        "biomeDisplayName", safe(environment.getBiomeDisplayName()),
                        "raining", Boolean.toString(environment.isRaining()),
                        "thundering", Boolean.toString(environment.isThundering()),
                        "dayTimeTicks", Long.toString(environment.getDayTimeTicks())
                ),
                List.of("world", "environment"),
                65,
                System.currentTimeMillis(),
                120_000L,
                System.currentTimeMillis()
        ));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }
}
