package betterbundle;

import betterbundle.gui.BetterBundleConfigScreen;
import betterbundle.gui.BundlePanelInteraction;
import betterbundle.util.AutoDepositManager;
import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

public class BetterBundleMod implements ClientModInitializer {
    public static final String MOD_ID = "better-bundle";

    private static KeyMapping openConfigKey;

    @Override
    public void onInitializeClient() {
        BetterBundleConfig.load();

        KeyMapping.Category category = KeyMapping.Category.register(
            Identifier.fromNamespaceAndPath(MOD_ID, "main"));
        openConfigKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
            "key.better-bundle.open_config", InputConstants.Type.KEYBOARD, InputConstants.KEY_F9, category));

        ClientTickEvents.END_CLIENT_TICK.register(AutoDepositManager::tick);
        ClientTickEvents.END_CLIENT_TICK.register(BetterBundleMod::onClientTick);
    }

    private static void onClientTick(Minecraft client) {
        BundlePanelInteraction.tick();

        while (openConfigKey.consumeClick()) {
            if (client.player != null) {
                client.gui.setScreen(new BetterBundleConfigScreen(client.gui.screen()));
            }
        }

        int reload = BetterBundleConfig.tickReload();
        if (reload != 0 && client.player != null) {
            client.player.sendOverlayMessage(Component.translatable(reload > 0
                ? "message.better-bundle.config_reloaded"
                : "message.better-bundle.config_reload_failed"));
        }
    }
}
