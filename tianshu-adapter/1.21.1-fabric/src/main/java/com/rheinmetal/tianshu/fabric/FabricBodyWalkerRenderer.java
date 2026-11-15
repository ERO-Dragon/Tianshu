package com.rheinmetal.tianshu.fabric;

import com.rheinmetal.tianshu.mc.body.WalkerBody;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.resources.DefaultPlayerSkin;
import net.minecraft.resources.ResourceLocation;

/**
 * The default look of a walking body: a humanoid with the default player skin.
 *
 * <p>The platform does not render bodies; this is simply the built-in carrier's own choice, and a third-party
 * carrier brings its own renderer instead.</p>
 */
public final class FabricBodyWalkerRenderer extends LivingEntityRenderer<WalkerBody, HumanoidModel<WalkerBody>> {

    public FabricBodyWalkerRenderer(EntityRendererProvider.Context context) {
        super(context, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER)), 0.5F);
    }

    @Override
    public ResourceLocation getTextureLocation(WalkerBody entity) {
        return DefaultPlayerSkin.getDefaultTexture();
    }
}