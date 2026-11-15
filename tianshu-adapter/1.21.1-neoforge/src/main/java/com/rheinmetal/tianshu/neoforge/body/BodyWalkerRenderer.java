package com.rheinmetal.tianshu.neoforge.body;

import com.rheinmetal.tianshu.mc.body.WalkerBody;
import net.minecraft.client.renderer.entity.EntityRenderer;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;

/**
 * Draws nothing: a walking body is a carrier, not a character.
 *
 * <p>It still has to exist, because a tracked entity without a renderer is a client crash. The texture is the
 * renderer contract, not used by the empty draw.</p>
 */
public final class BodyWalkerRenderer extends EntityRenderer<WalkerBody> {

    private static final ResourceLocation TEXTURE =
            ResourceLocation.withDefaultNamespace("textures/misc/white.png");

    public BodyWalkerRenderer(EntityRendererProvider.Context context) {
        super(context);
    }

    @Override
    public ResourceLocation getTextureLocation(WalkerBody entity) {
        return TEXTURE;
    }
}