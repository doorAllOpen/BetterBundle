package betterbundle.mixin;

import betterbundle.BetterBundleConfig;
import betterbundle.util.PacketDebug;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Logs the inventory packets the client actually sends when debugPackets is enabled. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientPacketSendMixin {

    private static final Logger LOG = LoggerFactory.getLogger("better-bundle-packets");

    @Inject(method = "send", at = @At("HEAD"))
    private void betterbundle$logSend(Packet<?> packet, CallbackInfo ci) {
        if (!BetterBundleConfig.get().debugPackets) return;
        long now = System.currentTimeMillis();

        if (packet instanceof ServerboundSelectBundleItemPacket p) {
            LOG.info("[BB-PKT-SEND {}] SELECT slot={} index={}",
                now, p.slotId(), p.selectedItemIndex());
        } else if (packet instanceof ServerboundContainerClickPacket p) {
            Minecraft mc = Minecraft.getInstance();
            String local = "";
            if (mc.player != null) {
                local = " localCarried=" + PacketDebug.describe(mc.player.containerMenu.getCarried())
                    + " localState=" + mc.player.containerMenu.getStateId();
            }
            LOG.info("[BB-PKT-SEND {}] CLICK container={} state={} slot={} button={} input={} changed={} carriedHash={}{}",
                now, p.containerId(), p.stateId(), p.slotNum(), p.buttonNum(),
                p.containerInput(), p.changedSlots(), p.carriedItem(), local);
        }
    }
}
