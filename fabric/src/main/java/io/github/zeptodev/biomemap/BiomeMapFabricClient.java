package io.github.zeptodev.biomemap;

import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;

/**
 * Fabric client entry point. Lets a singleplayer map end with links that open the files (see
 * {@link Platform#sendToHost}).
 */
public final class BiomeMapFabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        BiomeMapFabric.localChat = message -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> minecraft.gui.getChat().addMessage(message));
        };
    }
}
