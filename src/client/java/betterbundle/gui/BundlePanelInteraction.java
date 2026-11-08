package betterbundle.gui;

import betterbundle.BetterBundleConfig;
import betterbundle.util.BundleContentsHelper;
import betterbundle.util.BundleRuleMatcher;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundSelectBundleItemPacket;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Panel interaction. Automated click sequences are queued and spaced out by a few ticks
 * so the server can confirm each step; on a server the client's prediction alone is not
 * enough, and firing a whole burst at once caused desync (wrong item, or picking up the
 * bundle itself when the server-side cursor was empty).
 */
public final class BundlePanelInteraction {

    private static final int GLFW_MOD_SHIFT = 0x1;

    private record Step(int delayTicks, Runnable action) {}

    private static final Deque<Step> steps = new ArrayDeque<>();
    private static int waitTicks;
    private static AbstractContainerMenu lastMenu;

    private BundlePanelInteraction() {}

    /** Ticks between automated clicks: longer on servers so state syncs can arrive. */
    private static int stepDelay() {
        return Minecraft.getInstance().hasSingleplayerServer()
            ? 1 : Math.max(1, BetterBundleConfig.get().serverStepDelayTicks);
    }

    public static boolean isBusy() {
        return !steps.isEmpty() || waitTicks > 0;
    }

    /** Called from the client tick event. */
    public static void tick() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || !(mc.gui.screen() instanceof AbstractContainerScreen<?>)) {
            steps.clear();
            waitTicks = 0;
            return;
        }
        // Slot indexes are menu-specific: cancel queued steps when the menu changes.
        if (mc.player.containerMenu != lastMenu) {
            lastMenu = mc.player.containerMenu;
            steps.clear();
            waitTicks = 0;
            return;
        }
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }
        Step step = steps.poll();
        if (step != null) {
            step.action().run();
            waitTicks = step.delayTicks();
        }
    }

    private static void resetQueue() {
        steps.clear();
        waitTicks = 0;
    }

    private static void abort() {
        steps.clear();
        waitTicks = stepDelay();
    }

    private static void delayAfterAction() {
        waitTicks = stepDelay();
    }

    private static boolean carriedEmpty(Player player) {
        return player.containerMenu.getCarried().isEmpty();
    }

    private static BundlePanelRenderer.FlatItem getClickedItem(double mouseX, double mouseY,
                                                               int leftPos, int topPos) {
        List<BundlePanelRenderer.FlatItem> items = BundlePanelRenderer.getSelectedItems();
        if (items.isEmpty()) return null;

        int gx = BundlePanelRenderer.gridX(leftPos);
        int gy = BundlePanelRenderer.gridY(topPos);
        int col = ((int) mouseX - gx) / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);
        int row = ((int) mouseY - gy) / (BundlePanelRenderer.SLOT_SIZE + BundlePanelRenderer.SLOT_SPACING);

        if (col < 0 || col >= BundlePanelRenderer.COLUMNS) return null;
        if (row < 0 || row >= BundlePanelRenderer.VISIBLE_ROWS) return null;

        int flatIndex = (BundlePanelRenderer.getScrollOffset() + row) * BundlePanelRenderer.COLUMNS + col;
        if (flatIndex >= items.size()) return null;
        return items.get(flatIndex);
    }

    /**
     * Cursor empty.
     * Left-click a bundle: select it.
     * Left-click an item: take the whole stack. Right-click: take half.
     * Shift+left-click: move the whole stack into the player inventory.
     */
    public static boolean handlePanelClick(double mouseX, double mouseY, int button, int modifiers,
                                           int leftPos, int topPos, int imageHeight,
                                           AbstractContainerScreen<?> screen) {
        if (isBusy()) return true;

        int bundleSlot = BundlePanelRenderer.getBundleAt(mouseX, mouseY, leftPos, topPos, imageHeight);
        if (bundleSlot >= 0) {
            BundlePanelRenderer.selectBundle(bundleSlot);
            return true;
        }

        BundlePanelRenderer.FlatItem clicked = getClickedItem(mouseX, mouseY, leftPos, topPos);
        if (clicked == null) return false;

        Player player = Minecraft.getInstance().player;
        if (player == null) return false;
        if (!carriedEmpty(player)) return true;

        boolean shiftDown = (modifiers & GLFW_MOD_SHIFT) != 0;
        boolean rotate = "ROTATE".equalsIgnoreCase(BetterBundleConfig.get().extractionMode);

        if (rotate) {
            if (button == 1 && !shiftDown) notifyRotateHalf(player);
            startRotate(player, clicked.bundleSlot(), clicked.stack(), shiftDown);
            return true;
        }

        if (shiftDown) {
            startTakeWholeToInventory(player, clicked.bundleSlot(), clicked.itemIndex());
        } else if (button == 1) {
            startTakeHalf(player, clicked.bundleSlot(), clicked.itemIndex(), clicked.stack().getCount());
        } else {
            startTakeWhole(player, clicked.bundleSlot(), clicked.itemIndex());
        }
        return true;
    }

    /**
     * Server-safe extraction that never uses the selection packet. It removes the stacks
     * in front of the wanted one into temporary slots, takes the wanted stack, then puts
     * the others back. Slower and needs empty slots, but works on servers that ignore
     * ServerboundSelectBundleItemPacket.
     */
    private static void startRotate(Player player, int bundleSlot, ItemStack target, boolean toInventory) {
        resetQueue();
        if (!carriedEmpty(player)) {
            delayAfterAction();
            return;
        }

        BundleContents contents = BundleContentsHelper.getContents(currentBundle(player, bundleSlot));
        if (contents == null) {
            delayAfterAction();
            return;
        }
        List<ItemStack> items = contents.itemCopies().toList();
        int index = -1;
        for (int i = 0; i < items.size(); i++) {
            if (ItemStack.isSameItemSameComponents(items.get(i), target)) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            delayAfterAction();
            return;
        }

        // Make sure a leftover selection is cleared: with none, a right-click removes index 0.
        ItemStack bundle = currentBundle(player, bundleSlot);
        if (BundleItem.getSelectedItemIndex(bundle) != -1) {
            BundleItem.toggleSelectedItem(bundle, -1);
        }
        sendSelectPacket(bundleSlot, -1);

        int delay = stepDelay();
        List<Integer> temps = new ArrayList<>();
        int[] desiredSlot = {-1};

        if (index == 0) {
            steps.add(new Step(selectClickDelay(), () -> {
                if (carriedEmpty(player)) clickExtract(player, bundleSlot);
            }));
            if (toInventory) {
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) return;
                    int slot = findEmptyPlayerSlot(player, -1);
                    if (slot >= 0) click(player, slot, 0);
                }));
            }
            waitTicks = selectClickDelay();
            return;
        }

        for (int i = 0; i < index; i++) {
            steps.add(new Step(delay, () -> {
                if (!carriedEmpty(player)) { abort(); return; }
                clickExtract(player, bundleSlot);
            }));
            steps.add(new Step(delay, () -> {
                if (carriedEmpty(player)) { abort(); return; }
                int slot = findEmptyPlayerSlot(player, -1);
                if (slot < 0) { notifyRotateNoSpace(player); abort(); return; }
                temps.add(slot);
                click(player, slot, 0);
            }));
        }

        // Wanted stack is now at index 0.
        steps.add(new Step(delay, () -> {
            if (!carriedEmpty(player)) { abort(); return; }
            clickExtract(player, bundleSlot);
        }));
        steps.add(new Step(delay, () -> {
            if (carriedEmpty(player)) { abort(); return; }
            int slot = findEmptyPlayerSlot(player, -1);
            if (slot < 0) { notifyRotateNoSpace(player); abort(); return; }
            desiredSlot[0] = slot;
            click(player, slot, 0);
        }));

        // Put the removed stacks back, last removed first, so the front order is restored.
        for (int j = index - 1; j >= 0; j--) {
            final int idx = j;
            steps.add(new Step(delay, () -> {
                if (!carriedEmpty(player) || temps.size() <= idx) { abort(); return; }
                click(player, temps.get(idx), 0);
            }));
            steps.add(new Step(delay, () -> {
                if (carriedEmpty(player)) { abort(); return; }
                clickInsert(player, bundleSlot);
            }));
        }

        if (!toInventory) {
            steps.add(new Step(delay, () -> {
                if (!carriedEmpty(player) || desiredSlot[0] < 0) { abort(); return; }
                click(player, desiredSlot[0], 0);
            }));
        }
        waitTicks = selectClickDelay();
    }

    private static void startTakeWhole(Player player, int bundleSlot, int itemIndex) {
        resetQueue();
        if (!carriedEmpty(player)) {
            delayAfterAction();
            return;
        }
        whenSelected(player, bundleSlot, itemIndex, () -> {
            if (carriedEmpty(player)) clickExtract(player, bundleSlot);
            else clearSelection(player, bundleSlot);
            delayAfterAction();
        });
    }

    private static void startTakeWholeToInventory(Player player, int bundleSlot, int itemIndex) {
        resetQueue();
        if (!carriedEmpty(player)) {
            delayAfterAction();
            return;
        }
        int empty = findEmptyPlayerSlot(player, -1);
        whenSelected(player, bundleSlot, itemIndex, () -> {
            if (carriedEmpty(player)) clickExtract(player, bundleSlot);
            else clearSelection(player, bundleSlot);
            steps.add(new Step(stepDelay(), () -> {
                if (!carriedEmpty(player) && empty >= 0) click(player, empty, 0);
            }));
            waitTicks = stepDelay();
        });
    }

    /**
     * Bundle entries are whole stacks, so "half" is done with the normal inventory split.
     * Each step waits a few ticks for the server to confirm the previous one.
     */
    private static void startTakeHalf(Player player, int bundleSlot, int itemIndex, int count) {
        if (count <= 1) {
            startTakeWhole(player, bundleSlot, itemIndex);
            return;
        }
        int first = findEmptyPlayerSlot(player, -1);
        if (first < 0) {
            startTakeWhole(player, bundleSlot, itemIndex);
            return;
        }
        int second = findEmptyPlayerSlot(player, first);
        int delay = stepDelay();

        resetQueue();
        if (!carriedEmpty(player)) {
            delayAfterAction();
            return;
        }
        whenSelected(player, bundleSlot, itemIndex, () -> {
            if (carriedEmpty(player)) clickExtract(player, bundleSlot);
            else clearSelection(player, bundleSlot);

            if (second < 0) {
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) { abort(); return; }
                    click(player, first, 0);
                }));
                steps.add(new Step(delay, () -> {
                    if (!carriedEmpty(player)) { abort(); return; }
                    click(player, first, 1);
                }));
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) { abort(); return; }
                    clickInsert(player, bundleSlot);
                }));
                steps.add(new Step(delay, () -> {
                    if (!carriedEmpty(player)) { abort(); return; }
                    click(player, first, 0);
                }));
            } else {
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) { abort(); return; }
                    click(player, first, 0);
                }));
                steps.add(new Step(delay, () -> {
                    if (!carriedEmpty(player)) { abort(); return; }
                    click(player, first, 1);
                }));
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) { abort(); return; }
                    click(player, second, 0);
                }));
                steps.add(new Step(delay, () -> {
                    if (!carriedEmpty(player)) { abort(); return; }
                    click(player, first, 0);
                }));
                steps.add(new Step(delay, () -> {
                    if (carriedEmpty(player)) { abort(); return; }
                    clickInsert(player, bundleSlot);
                }));
                steps.add(new Step(delay, () -> {
                    if (!carriedEmpty(player)) { abort(); return; }
                    click(player, second, 0);
                }));
            }
            waitTicks = delay;
        });
    }

    /**
     * Selects the wanted item, then delays the extraction click. The selection index is not
     * part of BundleContents.equals(), so it is never synced back to the client; a
     * client-side confirmation is impossible. Instead mirror vanilla: one toggle packet,
     * and on servers wait long enough for it to be processed before the click.
     */
    private static int selectClickDelay() {
        return Minecraft.getInstance().hasSingleplayerServer()
            ? 1 : Math.max(1, BetterBundleConfig.get().serverSelectDelayTicks);
    }

    private static void whenSelected(Player player, int bundleSlot, int itemIndex, Runnable ready) {
        // Successful removals reset the selection on both sides, so a single toggle
        // selects the target. Always sending it also fixes a client whose local copy
        // happens to be stale (the selection is not part of the synced contents).
        ItemStack bundle = currentBundle(player, bundleSlot);
        BundleItem.toggleSelectedItem(bundle, itemIndex);
        sendSelectPacket(bundleSlot, itemIndex);
        steps.add(new Step(selectClickDelay(), ready));
        waitTicks = selectClickDelay();
    }

    private static void clearSelection(Player player, int bundleSlot) {
        ItemStack bundle = currentBundle(player, bundleSlot);
        if (BundleItem.getSelectedItemIndex(bundle) != -1) {
            BundleItem.toggleSelectedItem(bundle, -1);
            sendSelectPacket(bundleSlot, -1);
        }
    }

    private static ItemStack currentBundle(Player player, int bundleSlot) {
        return player.containerMenu.getSlot(bundleSlot).getItem();
    }

    private static void sendSelectPacket(int bundleSlot, int index) {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection != null) {
            connection.send(new ServerboundSelectBundleItemPacket(bundleSlot, index));
        }
    }

    /** Space+click on an inventory slot: move that stack into the selected bundle. */
    public static boolean handleSpaceClick(Slot hoveredSlot) {
        if (hoveredSlot == null || !hoveredSlot.hasItem()) return false;
        if (isBusy()) return true;

        Minecraft client = Minecraft.getInstance();
        if (client.player == null || client.getWindow() == null) return false;
        if (!InputConstants.isKeyDown(InputConstants.KEY_SPACE)) return false;

        Player player = client.player;
        ItemStack stack = hoveredSlot.getItem();
        if (stack.isEmpty() || BundleContentsHelper.isNonEmptyBundle(stack)) return false;

        BundlePanelRenderer.BundleSlotEntry target = findInsertTarget(stack);
        if (target == null) {
            boolean fit = anyBundleFits(stack);
            if (fit) notifyInsertBlocked(player);
            return fit;
        }

        click(player, hoveredSlot.index, 0);
        if (carriedEmpty(player)) return false;
        clickInsert(player, target.bundleSlot());
        delayAfterAction();
        return true;
    }

    private static int findEmptyPlayerSlot(Player player, int excludeMenuSlot) {
        for (int pass = 0; pass < 2; pass++) {
            int min = (pass == 0) ? 9 : 0;
            int max = (pass == 0) ? 36 : 9;
            for (Slot slot : player.containerMenu.slots) {
                if (slot.index == excludeMenuSlot) continue;
                if (slot.container != player.getInventory() || slot.hasItem()) continue;
                int idx = slot.getContainerSlot();
                if (idx >= min && idx < max) return slot.index;
            }
        }
        return -1;
    }

    private static long bulkInsertStart = 0;
    private static final long BULK_INSERT_DELAY = 50; // 0.05s

    public static void startBulkInsert() {
        bulkInsertStart = System.currentTimeMillis();
    }

    public static boolean isBulkInsertActive() {
        return bulkInsertStart > 0 && (System.currentTimeMillis() - bulkInsertStart) >= BULK_INSERT_DELAY;
    }

    public static void stopBulkInsert() {
        bulkInsertStart = 0;
    }

    /**
     * Cursor item + click in the panel: left-click puts everything into the clicked (or
     * selected) bundle, right-click puts exactly one item in.
     */
    public static boolean handlePanelInsert(double mouseX, double mouseY, int button,
                                            int leftPos, int topPos, int imageHeight) {
        if (isBusy()) return true;

        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return true;

        ItemStack cursor = player.containerMenu.getCarried();
        if (cursor.isEmpty()) return true;

        int clickedSlot = BundlePanelRenderer.getBundleAt(mouseX, mouseY, leftPos, topPos, imageHeight);
        BundlePanelRenderer.BundleSlotEntry target;
        if (clickedSlot >= 0) {
            BundlePanelRenderer.selectBundle(clickedSlot);
            target = findBundle(clickedSlot);
        } else {
            target = BundlePanelRenderer.getSelectedBundle();
        }

        if (target == null) return true;

        if (!BundleContentsHelper.canFitItem(target.bundleStack(), cursor.copyWithCount(1))) {
            notifyBundleFull(player);
            return true;
        }
        if (BundleRuleMatcher.blocksManualInsert(target.bundleStack(), cursor)) {
            notifyInsertBlocked(player);
            return true;
        }

        if (button == 1) {
            startInsertOne(player, target.bundleSlot());
        } else {
            resetQueue();
            clickInsert(player, target.bundleSlot());
            delayAfterAction();
        }
        return true;
    }

    private static void startInsertOne(Player player, int bundleSlot) {
        ItemStack cursor = player.containerMenu.getCarried();
        int count = cursor.getCount();
        if (count <= 1) {
            resetQueue();
            clickInsert(player, bundleSlot);
            delayAfterAction();
            return;
        }

        int first = findEmptyPlayerSlot(player, -1);
        int second = first < 0 ? -1 : findEmptyPlayerSlot(player, first);
        if (first < 0 || second < 0) {
            notifyNeedEmptySlots(player);
            return;
        }

        int delay = stepDelay();
        resetQueue();
        steps.add(new Step(delay, () -> {
            if (player.containerMenu.getCarried().getCount() != count) { abort(); return; }
            click(player, first, 1);
        }));
        steps.add(new Step(delay, () -> {
            if (player.containerMenu.getCarried().getCount() != count - 1) { abort(); return; }
            click(player, second, 0);
        }));
        steps.add(new Step(delay, () -> {
            if (!carriedEmpty(player)) { abort(); return; }
            click(player, first, 0);
        }));
        steps.add(new Step(delay, () -> {
            if (player.containerMenu.getCarried().getCount() != 1) { abort(); return; }
            clickInsert(player, bundleSlot);
        }));
        steps.add(new Step(delay, () -> {
            if (!carriedEmpty(player)) { abort(); return; }
            click(player, second, 0);
        }));
        waitTicks = delay;
    }

    /** Prefer the selected bundle; fall back to any bundle that accepts this stack. */
    private static BundlePanelRenderer.BundleSlotEntry findInsertTarget(ItemStack stack) {
        BundlePanelRenderer.BundleSlotEntry selected = BundlePanelRenderer.getSelectedBundle();
        if (selected != null
                && BundleContentsHelper.canFitItem(selected.bundleStack(), stack)
                && !BundleRuleMatcher.blocksManualInsert(selected.bundleStack(), stack)) {
            return selected;
        }
        for (BundlePanelRenderer.BundleSlotEntry entry : BundlePanelRenderer.getAllBundles()) {
            if (entry == selected) continue;
            if (!BundleContentsHelper.canFitItem(entry.bundleStack(), stack)) continue;
            if (BundleRuleMatcher.blocksManualInsert(entry.bundleStack(), stack)) continue;
            return entry;
        }
        return null;
    }

    private static boolean anyBundleFits(ItemStack stack) {
        for (BundlePanelRenderer.BundleSlotEntry entry : BundlePanelRenderer.getAllBundles()) {
            if (BundleContentsHelper.canFitItem(entry.bundleStack(), stack)) return true;
        }
        return false;
    }

    private static BundlePanelRenderer.BundleSlotEntry findBundle(int bundleSlot) {
        for (BundlePanelRenderer.BundleSlotEntry entry : BundlePanelRenderer.getAllBundles()) {
            if (entry.bundleSlot() == bundleSlot) return entry;
        }
        return null;
    }

    /**
     * Use the normal client click path so the client predicts each step and the packet
     * carries the correct state id and carried-item hash.
     */
    private static void click(Player player, int slot, int button) {
        Minecraft client = Minecraft.getInstance();
        if (client.gameMode == null) return;
        client.gameMode.handleContainerInput(
            player.containerMenu.containerId, slot, button, ContainerInput.PICKUP, player);
    }

    /** Right-click extraction only works with an empty cursor. */
    private static void clickExtract(Player player, int bundleSlot) {
        if (!carriedEmpty(player)) return;
        click(player, bundleSlot, 1);
    }

    /** Left-clicking a bundle with an empty cursor picks the bundle itself up; never allow that. */
    private static void clickInsert(Player player, int bundleSlot) {
        if (carriedEmpty(player)) return;
        click(player, bundleSlot, 0);
    }

    private static long lastBlockedMessage;
    private static long lastFullMessage;
    private static long lastSlotsMessage;
    private static long lastRotateMessage;

    private static void notifyRotateHalf(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastRotateMessage < 800) return;
        lastRotateMessage = now;
        player.sendOverlayMessage(Component.translatable("message.better-bundle.rotate_half"));
    }

    private static void notifyRotateNoSpace(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastRotateMessage < 800) return;
        lastRotateMessage = now;
        player.sendOverlayMessage(Component.translatable("message.better-bundle.rotate_no_space"));
    }

    private static void notifyInsertBlocked(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastBlockedMessage < 800) return;
        lastBlockedMessage = now;
        player.sendOverlayMessage(Component.translatable("message.better-bundle.insert_blocked"));
    }

    private static void notifyBundleFull(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastFullMessage < 800) return;
        lastFullMessage = now;
        player.sendOverlayMessage(Component.translatable("message.better-bundle.bundle_full"));
    }

    private static void notifyNeedEmptySlots(Player player) {
        long now = System.currentTimeMillis();
        if (now - lastSlotsMessage < 800) return;
        lastSlotsMessage = now;
        player.sendOverlayMessage(Component.translatable("message.better-bundle.need_empty_slots"));
    }

    public static boolean handleScroll(double mouseX, double mouseY, double scrollDelta,
                                       int leftPos, int topPos, int imageHeight) {
        if (!BundlePanelRenderer.isEffectivelyVisible()) return false;
        int dir = scrollDelta > 0 ? -1 : 1;
        if (BundlePanelRenderer.isInsideBundleBar(mouseX, mouseY, leftPos, topPos, imageHeight)) {
            BundlePanelRenderer.scrollBundles(dir, imageHeight);
            return true;
        }
        if (isInsidePanel(mouseX, mouseY, leftPos, topPos, imageHeight)) {
            BundlePanelRenderer.scrollItems(dir);
            return true;
        }
        return false;
    }

    public static boolean isInsidePanel(double mouseX, double mouseY,
                                        int leftPos, int topPos, int imageHeight) {
        int pw = BundlePanelRenderer.panelWidth();
        int panelX = leftPos - pw - 4;
        int gx = BundlePanelRenderer.gridX(leftPos);
        if (mouseX < gx || mouseX > panelX + pw - BundlePanelRenderer.PADDING) return false;
        int pTop = BundlePanelRenderer.gridY(topPos);
        int pH = BundlePanelRenderer.VISIBLE_ROWS * BundlePanelRenderer.SLOT_SIZE
                + (BundlePanelRenderer.VISIBLE_ROWS - 1) * BundlePanelRenderer.SLOT_SPACING;
        return mouseY >= pTop && mouseY <= pTop + pH;
    }
}
