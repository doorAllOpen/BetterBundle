package betterbundle.mixin;

import betterbundle.BetterBundleConfig;
import betterbundle.util.PacketDebug;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.game.ClientboundContainerSetContentPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the server's slot/content corrections when debugPackets is enabled. */
@Mixin(ClientPacketListener.class)
public abstract class ClientPacketReceiveMixin {

    private static final Logger LOG = LoggerFactory.getLogger("better-bundle-packets");

    @Inject(method = "handleContainerSetSlot", at = @At("HEAD"))
    private void betterbundle$logSetSlot(ClientboundContainerSetSlotPacket packet, CallbackInfo ci) {
        if (!BetterBundleConfig.get().debugPackets) return;
        LOG.info("[BB-PKT-RECV {}] SET_SLOT container={} state={} slot={} item={}",
            System.currentTimeMillis(), packet.getContainerId(), packet.getStateId(),
            packet.getSlot(), PacketDebug.describe(packet.getItem()));
    }

    @Inject(method = "handleContainerContent", at = @At("HEAD"))
    private void betterbundle$logContent(ClientboundContainerSetContentPacket packet, CallbackInfo ci) {
        if (!BetterBundleConfig.get().debugPackets) return;

        StringBuilder bundles = new StringBuilder();
        for (int i = 0; i < packet.items().size(); i++) {
            ItemStack stack = packet.items().get(i);
            if (!stack.isEmpty() && stack.has(DataComponents.BUNDLE_CONTENTS)) {
                bundles.append("slot").append(i).append('=').append(PacketDebug.describe(stack)).append(';');
            }
        }
        LOG.info("[BB-PKT-RECV {}] CONTENT container={} state={} slots={} carried={} bundles=[{}]",
            System.currentTimeMillis(), packet.containerId(), packet.stateId(),
            packet.items().size(), PacketDebug.describe(packet.carriedItem()), bundles);
    }
}
