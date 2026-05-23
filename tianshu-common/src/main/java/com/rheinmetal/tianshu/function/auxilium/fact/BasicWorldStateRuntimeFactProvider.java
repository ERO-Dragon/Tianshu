package com.rheinmetal.tianshu.function.auxilium.fact;

import com.rheinmetal.tianshu.function.auxilium.AXRequest;
import com.rheinmetal.tianshu.function.auxilium.scope.AXScope;
import com.rheinmetal.tianshu.provider.WorldStateProvider;
import com.rheinmetal.tianshu.snapshot.PlayerStatusData;
import com.rheinmetal.tianshu.snapshot.WorldEnvironmentData;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public final class BasicWorldStateRuntimeFactProvider extends AbstractDirtyRuntimeFactProvider {
    private final WorldStateProvider worldStateProvider;

    public BasicWorldStateRuntimeFactProvider(WorldStateProvider worldStateProvider) {
        this.worldStateProvider = worldStateProvider;
    }

    @Override
    public String providerId() {
        return "llm.basic_world_state";
    }

    @Override
    protected String snapshotSignature(AXScope scope, AXRequest request) {
        if (worldStateProvider == null) {
            return "world_state:empty";
        }
        StringBuilder builder = new StringBuilder("world_state:");
        if (worldStateProvider.getPlayerState() != null) {
            PlayerStatusData status = worldStateProvider.getPlayerState().getPlayerStatus();
            builder.append("player=");
            if (status != null) {
                builder.append(status.getHealth()).append('|')
                        .append(status.getMaxHealth()).append('|')
                        .append(status.getHunger()).append('|')
                        .append(status.getExperienceLevel());
            }
            builder.append('|').append(safe(worldStateProvider.getPlayerState().getCurrentDimensionDisplayName()));
        }
        if (worldStateProvider.getEnvironment() != null) {
            WorldEnvironmentData environment = worldStateProvider.getEnvironment().getWorldEnvironmentInfo();
            builder.append("|environment=");
            if (environment != null) {
                builder.append(environment.isRaining()).append('|')
                        .append(environment.isThundering()).append('|')
                        .append(environment.getDayTimeTicks()).append('|')
                        .append(safe(environment.getBiomeId())).append('|')
                        .append(safe(environment.getBiomeDisplayName()));
            }
        }
        return builder.toString();
    }

    @Override
    protected List<RuntimeFact> collectFacts(AXScope scope, AXRequest request) {
        if (worldStateProvider == null) {
            return List.of();
        }
        List<RuntimeFact> facts = new ArrayList<>();
        appendPlayerStatus(facts);
        appendEnvironment(facts);
        return facts;
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
                        "dimensionDisplayName", safe(worldStateProvider.getPlayerState().getCurrentDimensionDisplayName()),
                        "health", String.format("%.1f/%.1f", status.getHealth(), status.getMaxHealth()),
                        "hunger", Integer.toString(status.getHunger()),
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
