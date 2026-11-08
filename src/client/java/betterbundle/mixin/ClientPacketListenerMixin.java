package betterbundle.mixin;

import betterbundle.util.AutoDepositManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundTakeItemEntityPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientPacketListener.class)
public abstract class ClientPacketListenerMixin {

    @Inject(method = "handleTakeItemEntity", at = @At("TAIL"))
    private void betterbundle$onTakeItemEntity(ClientboundTakeItemEntityPacket packet, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null && packet.getPlayerId() == mc.player.getId()) {
            AutoDepositManager.onPickup(packet.getPlayerId());
        }
    }
}
