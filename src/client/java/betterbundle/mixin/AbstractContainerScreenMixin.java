package betterbundle.mixin;

import betterbundle.gui.BundlePanelInteraction;
import betterbundle.gui.BundlePanelRenderer;
import betterbundle.util.BundleContentsHelper;
import betterbundle.util.BundleRuleMatcher;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AbstractContainerScreen.class)
public abstract class AbstractContainerScreenMixin {

    @Inject(method = "mouseClicked", at = @At("HEAD"), cancellable = true)
    private void onMouseClicked(MouseButtonEvent event, boolean doubleClick, CallbackInfoReturnable<Boolean> cir) {
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        double mx = event.x();
        double my = event.y();

        // Managed bundles only accept matching items for manual insertion (configurable).
        // Cancel before the click is predicted locally and sent to the server.
        // SDL button codes: 1 = left, 3 = right (0/2 are not mouse buttons here).
        if ((event.button() == 1 || event.button() == 3) && blocksManualBundleInsert(self, mx, my)) {
            showInsertBlocked();
            cir.setReturnValue(true);
            return;
        }

        // Bulk-insert: space+left anywhere starts the timer (0.05s to activate)
        if (event.button() == 1 && isSpaceDown()) {
            BundlePanelInteraction.startBulkInsert();
        }

        // Space+Click works on ALL container screens
        Slot hovered = findSlotUnderMouse(self, mx, my);
        if (hovered != null && hovered.hasItem()) {
            boolean handled = BundlePanelInteraction.handleSpaceClick(hovered);
            if (handled) { cir.setReturnValue(true); return; }
        }

        // For non-recipe-book screens: handle toggle and search bar
        if (!(((Object) this) instanceof AbstractRecipeBookScreen)) {
            int bx = self.leftPos + self.imageWidth;
            int by = self.topPos + 5;
            if (mx >= bx && mx < bx + 20 && my >= by && my < by + 20) {
                BundlePanelRenderer.toggleVisible();
                cir.setReturnValue(true);
                return;
            }

            if (BundlePanelRenderer.isEffectivelyVisible()
                    && BundlePanelRenderer.isInsideSearchBar(mx, my, self.leftPos, self.topPos)) {
                BundlePanelRenderer.searchFocused = true;
                cir.setReturnValue(true);
                return;
            }

            BundlePanelRenderer.searchFocused = false;
        }

        if (!BundlePanelRenderer.isEffectivelyVisible()) return;

        // Cursor has items + click inside the panel → insert into the clicked/selected bundle.
        ItemStack cursor = self.getMenu().getCarried();
        if (!cursor.isEmpty() && isInsidePanelBounds(mx, my, self.leftPos, self.topPos, self.imageHeight)) {
            BundlePanelInteraction.handlePanelInsert(
                    mx, my, menuButton(event.button()), self.leftPos, self.topPos, self.imageHeight);
            cir.setReturnValue(true);
            return;
        }

        // Empty cursor: click a bundle icon to select it, or click an item to take it out.
        if (isInsidePanelBounds(mx, my, self.leftPos, self.topPos, self.imageHeight)) {
            BundlePanelInteraction.handlePanelClick(mx, my, menuButton(event.button()), event.modifiers(),
                    self.leftPos, self.topPos, self.imageHeight, self);
            // Never let a click inside the panel fall through to the container underneath.
            cir.setReturnValue(true);
        }
    }

    /**
     * Second line of defence: cancel the actual slot click (local prediction AND packet)
     * whenever a managed bundle would accept an item that does not match its rule.
     */
    @Inject(
        method = "slotClicked(Lnet/minecraft/world/inventory/Slot;IILnet/minecraft/world/inventory/ContainerInput;)V",
        at = @At("HEAD"),
        cancellable = true)
    private void betterbundle$blockUnmatchedSlotClick(Slot slot, int slotId, int button,
                                                      ContainerInput input, CallbackInfo ci) {
        if (input != ContainerInput.PICKUP || slot == null) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        ItemStack carried = self.getMenu().getCarried();
        if (carried.isEmpty()) return;

        ItemStack slotStack = slot.getItem();
        if (BundleContentsHelper.isBundle(slotStack)
                && BundleRuleMatcher.blocksManualInsert(slotStack, carried)) {
            showInsertBlocked();
            ci.cancel();
            return;
        }
        if (BundleContentsHelper.isBundle(carried)
                && !slotStack.isEmpty()
                && BundleRuleMatcher.blocksManualInsert(carried, slotStack)) {
            showInsertBlocked();
            ci.cancel();
            return;
        }

        // Throttle bundle insertions. On a server, clicking faster than the state sync can
        // let a click arrive while the server-side cursor is stale, which used to bypass
        // the check or pick up the bundle by accident.
        if (BundleContentsHelper.isBundle(slotStack) || BundleContentsHelper.isBundle(carried)) {
            long now = System.currentTimeMillis();
            if (now - lastBundleSlotClickAt < 200) {
                ci.cancel();
                return;
            }
            lastBundleSlotClickAt = now;
        }
    }

    private static long lastBundleSlotClickAt;

    private static long lastBlockedMessage;

    private static void showInsertBlocked() {
        long now = System.currentTimeMillis();
        if (now - lastBlockedMessage < 800) return;
        lastBlockedMessage = now;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.player.sendOverlayMessage(Component.translatable("message.better-bundle.insert_blocked"));
        }
    }

    private static Slot findSlotUnderMouse(AbstractContainerScreen<?> screen, double mx, double my) {
        for (Slot slot : screen.getMenu().slots) {
            if (!slot.isActive()) continue;
            double sx = screen.leftPos + slot.x;
            double sy = screen.topPos + slot.y;
            if (mx >= sx && mx < sx + 16 && my >= sy && my < sy + 16) {
                return slot;
            }
        }
        return null;
    }

    private static boolean blocksManualBundleInsert(AbstractContainerScreen<?> screen, double mx, double my) {
        Slot hovered = findSlotUnderMouse(screen, mx, my);
        if (hovered == null) return false;

        ItemStack carried = screen.getMenu().getCarried();
        if (carried.isEmpty()) return false;
        ItemStack slotStack = hovered.getItem();

        // Item on the cursor, bundle in the slot. Bundles can contain other bundles,
        // so this must apply to bundle-on-bundle too.
        if (BundleContentsHelper.isBundle(slotStack)) {
            return BundleRuleMatcher.blocksManualInsert(slotStack, carried);
        }
        // Bundle on the cursor, item in the slot.
        if (BundleContentsHelper.isBundle(carried) && !slotStack.isEmpty()) {
            return BundleRuleMatcher.blocksManualInsert(carried, slotStack);
        }
        return false;
    }

    private static boolean isInsidePanelBounds(double mx, double my, int leftPos, int topPos, int imageHeight) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        int panelH = BundlePanelRenderer.panelHeight(imageHeight);
        return mx >= panelX && mx <= panelX + pw && my >= topPos && my <= topPos + panelH;
    }

    @Inject(method = "mouseReleased", at = @At("HEAD"), cancellable = true)
    private void onMouseReleased(MouseButtonEvent event, CallbackInfoReturnable<Boolean> cir) {
        BundlePanelInteraction.stopBulkInsert();
        lastBulkSlot = -1;
        if (!BundlePanelRenderer.isEffectivelyVisible()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (isInsidePanelBounds(event.x(), event.y(),
                self.leftPos, self.topPos, self.imageHeight)) {
            cir.setReturnValue(true);
        }
    }

    @Inject(method = "keyPressed", at = @At("HEAD"), cancellable = true)
    private void onKeyPressed(KeyEvent event, CallbackInfoReturnable<Boolean> cir) {
        if (BundlePanelRenderer.searchFocused) {
            BundlePanelRenderer.onSearchKeyPress(event.key());
            cir.setReturnValue(true);
        }
    }

    private int lastBulkSlot = -1;

    @Inject(method = "mouseDragged", at = @At("HEAD"), cancellable = true)
    private void onMouseDragged(MouseButtonEvent event, double dx, double dy,
                                 CallbackInfoReturnable<Boolean> cir) {
        if (!BundlePanelInteraction.isBulkInsertActive()) return;
        if (!isSpaceDown()) { BundlePanelInteraction.stopBulkInsert(); return; }

        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        Slot hovered = self.hoveredSlot;
        if (hovered != null && hovered.hasItem() && hovered.index != lastBulkSlot) {
            lastBulkSlot = hovered.index;
            BundlePanelInteraction.handleSpaceClick(hovered);
        }
        cir.setReturnValue(true);
    }

    @Inject(method = "mouseScrolled", at = @At("HEAD"), cancellable = true)
    private void onMouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!BundlePanelRenderer.isEffectivelyVisible()) return;
        AbstractContainerScreen<?> self = (AbstractContainerScreen<?>) (Object) this;
        if (isInsidePanelBounds(mouseX, mouseY,
                self.leftPos, self.topPos, self.imageHeight)) {
            boolean handled = BundlePanelInteraction.handleScroll(mouseX, mouseY, scrollY,
                    self.leftPos, self.topPos, self.imageHeight);
            if (handled) cir.setReturnValue(true);
        }
    }

    /** SDL mouse codes are 1 = left, 3 = right; container clicks use 0 = left, 1 = right. */
    private static int menuButton(int eventButton) {
        return eventButton == 3 ? 1 : 0;
    }

    private static boolean isSpaceDown() {
        return InputConstants.isKeyDown(InputConstants.KEY_SPACE);
    }
}
