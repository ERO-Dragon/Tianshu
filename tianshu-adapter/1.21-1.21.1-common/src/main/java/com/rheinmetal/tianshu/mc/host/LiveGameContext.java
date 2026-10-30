package com.rheinmetal.tianshu.mc.host;

import com.rheinmetal.tianshu.api.LogSink;
import com.rheinmetal.tianshu.client.host.game.ClientGameContextPort;
import com.rheinmetal.tianshu.client.host.game.GameBlockTarget;
import com.rheinmetal.tianshu.client.host.game.GameContainerEntry;
import com.rheinmetal.tianshu.client.host.game.GameContainerView;
import com.rheinmetal.tianshu.client.host.game.GameEffect;
import com.rheinmetal.tianshu.client.host.game.GameEquipment;
import com.rheinmetal.tianshu.client.host.game.GameInteractionContext;
import com.rheinmetal.tianshu.client.host.game.GameInventoryEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerListEntry;
import com.rheinmetal.tianshu.client.host.game.GamePlayerPose;
import com.rheinmetal.tianshu.client.host.game.GamePlayerStatus;
import com.rheinmetal.tianshu.client.host.game.GamePlayerVitals;
import com.rheinmetal.tianshu.client.host.game.GameScreenKind;
import com.rheinmetal.tianshu.client.host.game.GameTarget;
import com.rheinmetal.tianshu.client.host.game.GameWorldEnvironment;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.PlayerInfo;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The platform half of the game reading: reads live Minecraft objects and hands back values.
 *
 * <p>Every method checks that a world is actually running; a call outside a world answers with the empty value
 * instead of throwing, because the consumer is a feature module and a missing world is a normal state, not a bug.
 * Display names are resolved here — the registry plus the client language are the only places that can turn an
 * id into words the player recognizes.</p>
 *
 * <p>Nothing is cached: a capture is what the game looks like at this instant.</p>
 */
public final class LiveGameContext implements ClientGameContextPort {
    private final GameScreenClassifier screenClassifier = new GameScreenClassifier();
    private final ClientLanguageSnapshot languageSnapshot;
    private final LogSink logSink;

    public LiveGameContext(ClientLanguageSnapshot languageSnapshot) {
        this(languageSnapshot, LogSink.NOOP);
    }

    public LiveGameContext(ClientLanguageSnapshot languageSnapshot, LogSink logSink) {
        this.languageSnapshot = Objects.requireNonNull(languageSnapshot, "languageSnapshot");
        this.logSink = logSink == null ? LogSink.NOOP : logSink;
    }

    @Override
    public String playerId() {
        Player player = player();
        return player == null ? "" : player.getStringUUID();
    }

    @Override
    public GameInteractionContext captureInteraction() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = player();
        if (player == null || minecraft.level == null) {
            return GameInteractionContext.empty();
        }
        String languageCode = languageSnapshot.languageCode();
        Screen screen = minecraft.screen;
        return new GameInteractionContext(
                minecraft.level.dimension().location().toString(),
                ClientLanguagePolicy.registryDisplayName(
                        minecraft.level.dimension().location(), "dimension", languageCode),
                screenClassifier.classify(screen),
                itemId(player.getMainHandItem()),
                ClientLanguagePolicy.itemDisplayName(player.getMainHandItem(),
                        BuiltInRegistries.ITEM.getKey(player.getMainHandItem().getItem()), languageCode),
                equippedItemIds(player),
                crosshairTarget(minecraft, player),
                minecraft.options.keyUse.isDown(),
                minecraft.options.keyAttack.isDown(),
                player.isShiftKeyDown(),
                System.currentTimeMillis()
        );
    }

    @Override
    public GamePlayerStatus capturePlayerStatus() {
        Player player = player();
        if (player == null) {
            return GamePlayerStatus.EMPTY;
        }
        return new GamePlayerStatus(
                player.getHealth(),
                player.getMaxHealth(),
                player.getFoodData().getFoodLevel(),
                player.experienceLevel
        );
    }

    @Override
    public List<GameInventoryEntry> captureInventory() {
        Player player = player();
        if (player == null) {
            return List.of();
        }
        Inventory inventory = player.getInventory();
        if (inventory == null) {
            return List.of();
        }
        String languageCode = languageSnapshot.languageCode();
        List<GameInventoryEntry> result = new ArrayList<>();
        for (int index = 0; index < inventory.getContainerSize(); index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            result.add(entry(stack, languageCode));
        }
        return List.copyOf(result);
    }

    @Override
    public GameEquipment captureEquipment() {
        Player player = player();
        if (player == null) {
            return GameEquipment.EMPTY;
        }
        String languageCode = languageSnapshot.languageCode();
        return new GameEquipment(
                entry(player.getItemBySlot(EquipmentSlot.HEAD), languageCode),
                entry(player.getItemBySlot(EquipmentSlot.CHEST), languageCode),
                entry(player.getItemBySlot(EquipmentSlot.LEGS), languageCode),
                entry(player.getItemBySlot(EquipmentSlot.FEET), languageCode),
                entry(player.getMainHandItem(), languageCode),
                entry(player.getOffhandItem(), languageCode),
                player.getArmorValue(),
                armorToughness(player)
        );
    }

    @Override
    public List<GameEffect> captureEffects() {
        Player player = player();
        if (player == null) {
            return List.of();
        }
        String languageCode = languageSnapshot.languageCode();
        List<GameEffect> result = new ArrayList<>();
        for (MobEffectInstance effect : player.getActiveEffects()) {
            try {
                ResourceLocation effectId = effect.getEffect().unwrapKey().map(key -> key.location()).orElse(null);
                result.add(new GameEffect(
                        effectId == null ? "" : effectId.toString(),
                        ClientLanguagePolicy.effectDisplayName(effectId,
                                effect.getEffect().value().getDescriptionId(), languageCode),
                        effect.getDuration(),
                        effect.getAmplifier(),
                        effect.getEffect().value().isBeneficial()
                ));
            } catch (RuntimeException exception) {
                logSink.warn("neoforge.game_context.effect_capture_failed detail=" + exception.getMessage());
            }
        }
        return List.copyOf(result);
    }

    @Override
    public GameWorldEnvironment captureEnvironment() {
        Player player = player();
        Minecraft minecraft = Minecraft.getInstance();
        if (player == null || minecraft.level == null) {
            return GameWorldEnvironment.EMPTY;
        }
        Level level = minecraft.level;
        String languageCode = languageSnapshot.languageCode();
        long dayTime = level.getDayTime() % 24000L;
        String biomeId = "";
        String biomeDisplayName = "";
        try {
            biomeId = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> key.location().toString())
                    .orElse("");
            biomeDisplayName = level.getBiome(player.blockPosition()).unwrapKey()
                    .map(key -> ClientLanguagePolicy.registryDisplayName(key.location(), "biome", languageCode))
                    .orElse(biomeId);
        } catch (RuntimeException exception) {
            logSink.warn("neoforge.game_context.biome_capture_failed detail=" + exception.getMessage());
        }
        return new GameWorldEnvironment(level.isRaining(), level.isThundering(), dayTime, biomeId, biomeDisplayName);
    }

    @Override
    public GameBlockTarget captureBlockTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.level == null
                || !(minecraft.hitResult instanceof BlockHitResult hitResult)) {
            return GameBlockTarget.EMPTY;
        }
        BlockPos position = hitResult.getBlockPos();
        BlockState state = minecraft.level.getBlockState(position);
        ResourceLocation key = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        return new GameBlockTarget(
                key == null ? "" : key.toString(),
                state.getBlock().getName().getString(),
                position.getX(),
                position.getY(),
                position.getZ(),
                true
        );
    }

    @Override
    public GamePlayerPose capturePlayerPose() {
        Minecraft minecraft = Minecraft.getInstance();
        Player player = player();
        if (player == null || minecraft.level == null) {
            return GamePlayerPose.EMPTY;
        }
        String gameMode = minecraft.gameMode == null ? "" : minecraft.gameMode.getPlayerMode().getName();
        return new GamePlayerPose(
                minecraft.level.dimension().location().toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.getYRot(),
                player.getXRot(),
                gameMode
        );
    }

    @Override
    public GamePlayerVitals captureVitals() {
        Player player = player();
        if (player == null) {
            return GamePlayerVitals.EMPTY;
        }
        return new GamePlayerVitals(
                player.experienceProgress,
                player.getFoodData().getSaturationLevel(),
                player.getAbsorptionAmount()
        );
    }

    @Override
    public GameContainerView captureOpenContainer() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !(minecraft.screen instanceof AbstractContainerScreen<?> screen)) {
            return GameContainerView.EMPTY;
        }
        String languageCode = languageSnapshot.languageCode();
        List<GameContainerEntry> entries = new ArrayList<>();
        for (Slot slot : screen.getMenu().slots) {
            ItemStack stack = slot.getItem();
            if (stack == null || stack.isEmpty()) {
                continue;
            }
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            entries.add(new GameContainerEntry(
                    slot.index,
                    key == null ? "" : key.toString(),
                    ClientLanguagePolicy.itemDisplayName(stack, key, languageCode),
                    stack.getCount()
            ));
        }
        String title = screen.getTitle() == null ? "" : screen.getTitle().getString();
        return new GameContainerView(title, entries);
    }

    @Override
    public List<GamePlayerListEntry> capturePlayerList() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getConnection() == null) {
            return List.of();
        }
        List<GamePlayerListEntry> result = new ArrayList<>();
        for (PlayerInfo info : minecraft.getConnection().getOnlinePlayers()) {
            if (info == null || info.getProfile() == null) {
                continue;
            }
            result.add(new GamePlayerListEntry(
                    info.getProfile().getName(),
                    info.getLatency(),
                    info.getGameMode() == null ? "" : info.getGameMode().getName()
            ));
        }
        return List.copyOf(result);
    }

    private Player player() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft == null ? null : minecraft.player;
    }

    private List<String> equippedItemIds(Player player) {
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        addItemId(player.getMainHandItem(), ids);
        addItemId(player.getOffhandItem(), ids);
        for (ItemStack stack : player.getArmorSlots()) {
            addItemId(stack, ids);
        }
        return List.copyOf(ids);
    }

    /**
     * One stack, named; an empty slot stays {@code null}, because "no chestplate" is not "an empty chestplate".
     */
    private GameInventoryEntry entry(ItemStack stack, String languageCode) {
        if (stack == null || stack.isEmpty()) {
            return null;
        }
        ResourceLocation itemKey = BuiltInRegistries.ITEM.getKey(stack.getItem());
        String itemId = itemKey == null ? stack.getItemHolder().getRegisteredName() : itemKey.toString();
        return new GameInventoryEntry(
                itemId,
                ClientLanguagePolicy.itemDisplayName(stack, itemKey, languageCode),
                stack.getCount(),
                stack.getMaxStackSize()
        );
    }

    private float armorToughness(Player player) {
        var attribute = player.getAttribute(Attributes.ARMOR_TOUGHNESS);
        return attribute == null ? 0.0F : (float) attribute.getValue();
    }

    private void addItemId(ItemStack stack, Set<String> ids) {
        String id = itemId(stack);
        if (!id.isBlank()) {
            ids.add(id);
        }
    }

    private String itemId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return "";
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private GameTarget crosshairTarget(Minecraft minecraft, Player player) {
        HitResult hitResult = minecraft.hitResult;
        if (!(hitResult instanceof EntityHitResult entityHitResult)) {
            return GameTarget.EMPTY;
        }
        Entity entity = entityHitResult.getEntity();
        ResourceLocation typeKey = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
        return new GameTarget(
                entity.getStringUUID(),
                typeKey == null ? "" : typeKey.toString(),
                entity.getDisplayName().getString(),
                player.distanceTo(entity),
                true
        );
    }
}
