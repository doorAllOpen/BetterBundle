package betterbundle.mixin;

import betterbundle.util.BundleRuleMatcher;
import net.minecraft.world.entity.SlotAccess;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ClickAction;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Enforces the matching rule at the point where a bundle actually accepts items.
 * This runs in the client JVM, so it also covers the integrated (singleplayer) server
 * and every inventory path, not just the vanilla mouse-click handler.
 */
@Mixin(BundleItem.class)
public abstract class BundleItemMixin {

    @Inject(method = "overrideOtherStackedOnMe", at = @At("HEAD"), cancellable = true)
    private void betterbundle$blockUnmatchedInsert(ItemStack bundleStack, ItemStack carriedStack,
                                                   Slot slot, ClickAction action, Player player,
                                                   SlotAccess access, CallbackInfoReturnable<Boolean> cir) {
        if (action == ClickAction.PRIMARY && !carriedStack.isEmpty()
                && BundleRuleMatcher.blocksManualInsert(bundleStack, carriedStack)) {
            // Consume the click so vanilla neither inserts the item nor falls through.
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "overrideStackedOnOther", at = @At("HEAD"), cancellable = true)
    private void betterbundle$blockUnmatchedTransfer(ItemStack bundleStack, Slot slot,
                                                     ClickAction action, Player player,
                                                     CallbackInfoReturnable<Boolean> cir) {
        ItemStack slotStack = slot.getItem();
        if (action == ClickAction.PRIMARY && !slotStack.isEmpty()
                && BundleRuleMatcher.blocksManualInsert(bundleStack, slotStack)) {
            cir.setReturnValue(true);
        }
    }
}
