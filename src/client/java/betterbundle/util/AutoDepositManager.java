package betterbundle.util;

import betterbundle.BetterBundleConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Client-side auto-deposit. Newly picked up items are moved into bundles whose custom name
 * follows "displayName<separator><regex>", for example:
 * "矿物_^minecraft:(diamond|emerald|redstone)(_ore)?$".
 *
 * Only normal container click packets are sent. In PICKED_UP_ONLY mode, when an item merged into
 * an existing stack, the picked-up delta is first split into an empty slot using one-item
 * right-clicks, then that delta stack is inserted into the bundle; the pre-existing items stay.
 * WHOLE_STACK mode instead deposits the entire stack, including items that were already there.
 */
public final class AutoDepositManager {

    private static final int INVENTORY_SIZE = 36;
    private static final int HISTORY_SIZE = 20;
    private static final int PICKUP_WINDOW = 15;

    private enum Phase {
        PICK_SOURCE,
        PLACE_ONE,
        RETURN_SOURCE,
        PICK_TEMP,
        INSERT_BUNDLE,
        RETURN_REMAINDER
    }

    private record Candidate(int sourceIndex, int pickedAmount) {}

    private static final class DepositTask {
        final int sourceIndex;
        final int bundleIndex;
        final int pickedAmount;
        final int tempIndex;
        final boolean split;
        Phase phase = Phase.PICK_SOURCE;
        int placed;

        DepositTask(int sourceIndex, int bundleIndex, int pickedAmount, int tempIndex, boolean split) {
            this.sourceIndex = sourceIndex;
            this.bundleIndex = bundleIndex;
            this.pickedAmount = pickedAmount;
            this.tempIndex = tempIndex;
            this.split = split;
        }
    }

    private static final Deque<DepositTask> tasks = new ArrayDeque<>();
    private static final Deque<Map<Integer, ItemStack>> history = new ArrayDeque<>();
    private static Map<Integer, ItemStack> pickupBaseline = null;

    private static ClientLevel lastLevel;
    private static Player lastPlayer;
    private static int pickupWindow;
    private static int cooldown;
    private static int suppress;

    private AutoDepositManager() {}

    /**
     * Whether the current screen should pause auto-deposit. Both cases are configurable:
     * containers default to "do not deposit" (the player may be moving items manually),
     * other screens such as the Esc menu default to "keep depositing".
     */
    private static boolean blocksAutomation(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (screen == null) return false;
        BetterBundleConfig config = BetterBundleConfig.get();
        if (screen instanceof AbstractContainerScreen<?>) {
            return !config.autoDepositInInventory;
        }
        return !config.autoDepositInMenus;
    }

    public static void onPickup(int playerId) {
        Minecraft mc = Minecraft.getInstance();
        BetterBundleConfig config = BetterBundleConfig.get();
        if (!config.autoDeposit) return;
        if (mc.player == null || mc.level == null) return;
        if (blocksAutomation(mc)) return;
        if (playerId != mc.player.getId()) return;

        // Keep the earliest settled baseline while pickups are still being processed. If a
        // second pickup arrives during a deposit, this lets a later scan compare against the
        // original state and catch the items that were picked up meanwhile instead of losing them.
        if (pickupBaseline == null) {
            Map<Integer, ItemStack> baseline = history.peekLast();
            pickupBaseline = baseline != null ? copy(baseline) : snapshot(mc.player);
        }
        pickupWindow = PICKUP_WINDOW;
    }

    public static void tick(Minecraft mc) {
        BetterBundleConfig config = BetterBundleConfig.get();
        Player player = mc.player;
        ClientLevel level = mc.level;

        if (player == null || level == null) {
            reset();
            return;
        }

        if (player != lastPlayer || level != lastLevel) {
            reset();
            lastPlayer = player;
            lastLevel = level;
        }

        // Do not automate while an inventory/container GUI is open: there the player may be
        // intentionally moving items, and cursor/slot state is user-owned.
        if (blocksAutomation(mc)) {
            tasks.clear();
            pickupWindow = 0;
            pickupBaseline = null;
            cooldown = 0;
            suppress = 0;
            history.clear();
            history.addLast(snapshot(player));
            return;
        }

        if (cooldown > 0) cooldown--;
        if (suppress > 0) suppress--;

        // Only scan when the queue is empty: scanning while a previous batch is still being
        // deposited would enqueue the same stacks twice. The window is kept alive while tasks run.
        if (pickupWindow > 0 && tasks.isEmpty() && cooldown == 0 && suppress == 0) {
            Map<Integer, ItemStack> baseline = pickupBaseline != null
                ? pickupBaseline : snapshot(player);
            List<Candidate> candidates = findCandidates(player, baseline);
            if (!candidates.isEmpty()) {
                for (Candidate candidate : candidates) {
                    enqueue(player, candidate.sourceIndex(), candidate.pickedAmount(), config);
                }
                pickupWindow = 0;
                // Keep pickupBaseline until this batch finishes, so pickups made during the
                // deposit can be detected by a follow-up scan against the same baseline.
            } else {
                pickupWindow = 0;
                pickupBaseline = null;
            }
        }

        if (!tasks.isEmpty() && cooldown == 0) {
            int budget = Math.max(1, config.maxDepositsPerTick);
            while (budget-- > 0 && !tasks.isEmpty() && cooldown == 0) {
                DepositTask task = tasks.peek();
                boolean done = task.split
                    ? executeSplitStep(mc, player, task, config)
                    : executeWhole(mc, player, task, config);
                if (done) {
                    tasks.poll();
                } else if (!task.split) {
                    break;
                }
                if (task.split) break;
            }
        }

        // Everything settled and no pickup window pending: the next pickup starts from a
        // fresh baseline again.
        if (tasks.isEmpty() && cooldown == 0 && suppress == 0 && pickupWindow == 0) {
            pickupBaseline = null;
        }

        Map<Integer, ItemStack> current = snapshot(player);
        history.addLast(copy(current));
        while (history.size() > HISTORY_SIZE) history.removeFirst();
    }

    private static void enqueue(Player player, int sourceIndex, int pickedAmount,
                                BetterBundleConfig config) {
        ItemStack source = player.getInventory().getItem(sourceIndex);
        if (source.isEmpty() || BundleContentsHelper.isBundle(source)) return;

        int bundleIndex = findMatchingBundle(player, source, sourceIndex);
        if (bundleIndex < 0) return;

        // Anything other than an explicit WHOLE_STACK means "only the new items", so a
        // missing/unknown value can never accidentally drain the pre-existing stack.
        boolean split = !"WHOLE_STACK".equalsIgnoreCase(config.depositMode)
            && pickedAmount > 0
            && pickedAmount < source.getCount();

        int tempIndex = -1;
        if (split) {
            tempIndex = findEmptySlot(player, sourceIndex, bundleIndex);
            if (tempIndex < 0 || pickedAmount > Math.max(1, config.maxSplitItems)) {
                // Only fall back to the whole stack when explicitly requested; the default
                // is to leave the inventory untouched rather than deposit existing items.
                if (!"WHOLE_STACK".equalsIgnoreCase(config.splitOverflow)) return;
                split = false;
            }
        }

        tasks.add(new DepositTask(sourceIndex, bundleIndex, pickedAmount, tempIndex, split));
    }

    private static boolean executeWhole(Minecraft mc, Player player, DepositTask task,
                                        BetterBundleConfig config) {
        if (!player.containerMenu.getCarried().isEmpty()) return false;

        ItemStack sourceStack = player.getInventory().getItem(task.sourceIndex);
        ItemStack bundleStack = player.getInventory().getItem(task.bundleIndex);
        if (sourceStack.isEmpty() || !BundleContentsHelper.isBundle(bundleStack)) return true;
        if (!BundleContentsHelper.canFitItem(bundleStack, sourceStack.copyWithCount(1))) return true;
        if (!BundleRuleMatcher.matches(bundleStack, sourceStack)) return true;

        Slot sourceSlot = findSlot(player, task.sourceIndex);
        Slot bundleSlot = findSlot(player, task.bundleIndex);
        if (sourceSlot == null || bundleSlot == null) return true;
        if (sourceSlot.index == bundleSlot.index) return true;

        click(mc, player, sourceSlot.index, 0);
        click(mc, player, bundleSlot.index, 0);
        // If the bundle could only take part of the stack, return the remainder.
        // When the bundle took everything the source slot is empty, so this click is a no-op.
        click(mc, player, sourceSlot.index, 0);
        finish(task, player, sourceStack, bundleStack, config);
        return true;
    }

    private static boolean executeSplitStep(Minecraft mc, Player player, DepositTask task,
                                            BetterBundleConfig config) {
        ItemStack sourceStack = player.getInventory().getItem(task.sourceIndex);
        ItemStack bundleStack = player.getInventory().getItem(task.bundleIndex);
        Slot sourceSlot = findSlot(player, task.sourceIndex);
        Slot bundleSlot = findSlot(player, task.bundleIndex);
        Slot tempSlot = findSlot(player, task.tempIndex);
        if (sourceSlot == null || bundleSlot == null || tempSlot == null) {
            return true;
        }
        if (!BundleContentsHelper.isBundle(bundleStack)) return true;

        int splitDelay = Math.max(1, config.splitClickDelayTicks);

        switch (task.phase) {
            case PICK_SOURCE -> {
                if (!player.containerMenu.getCarried().isEmpty()) return false;
                if (sourceStack.isEmpty()) return true;
                if (sourceStack.getCount() < task.pickedAmount) return true;
                click(mc, player, sourceSlot.index, 0);
                task.phase = Phase.PLACE_ONE;
                cooldown = splitDelay;
            }
            case PLACE_ONE -> {
                if (task.placed >= task.pickedAmount) {
                    task.phase = Phase.RETURN_SOURCE;
                    return false;
                }
                click(mc, player, tempSlot.index, 1);
                task.placed++;
                cooldown = splitDelay;
                if (task.placed >= task.pickedAmount) {
                    task.phase = Phase.RETURN_SOURCE;
                }
            }
            case RETURN_SOURCE -> {
                click(mc, player, sourceSlot.index, 0);
                task.phase = Phase.PICK_TEMP;
                cooldown = splitDelay;
            }
            case PICK_TEMP -> {
                if (!player.containerMenu.getCarried().isEmpty()) return false;
                click(mc, player, tempSlot.index, 0);
                task.phase = Phase.INSERT_BUNDLE;
                cooldown = splitDelay;
            }
            case INSERT_BUNDLE -> {
                click(mc, player, bundleSlot.index, 0);
                task.phase = Phase.RETURN_REMAINDER;
                cooldown = splitDelay;
            }
            case RETURN_REMAINDER -> {
                click(mc, player, tempSlot.index, 0);
                finish(task, player, sourceStack, bundleStack, config);
                return true;
            }
        }
        return false;
    }

    private static void finish(DepositTask task, Player player, ItemStack sourceStack,
                               ItemStack bundleStack, BetterBundleConfig config) {
        cooldown = Math.max(cooldown, config.depositDelayTicks);
        suppress = cooldown + 2;
        if (config.debug) {
            player.sendOverlayMessage(Component.literal(
                "[Better Bundle] deposited " + task.pickedAmount + "x "
                    + sourceStack.getHoverName().getString()
                    + " into " + bundleStack.getHoverName().getString()));
        }
    }

    private static List<Candidate> findCandidates(Player player, Map<Integer, ItemStack> baseline) {
        List<Candidate> result = new ArrayList<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            ItemStack now = player.getInventory().getItem(i);
            if (now.isEmpty() || BundleContentsHelper.isBundle(now)) continue;
            ItemStack old = baseline.getOrDefault(i, ItemStack.EMPTY);
            if (old.isEmpty() || !ItemStack.isSameItemSameComponents(now, old)) {
                result.add(new Candidate(i, now.getCount()));
            } else if (now.getCount() > old.getCount()) {
                result.add(new Candidate(i, now.getCount() - old.getCount()));
            }
        }
        return result;
    }

    private static int findMatchingBundle(Player player, ItemStack source, int sourceIndex) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (i == sourceIndex) continue;
            ItemStack bundle = player.getInventory().getItem(i);
            if (!BundleContentsHelper.isBundle(bundle)) continue;
            if (!BundleContentsHelper.canFitItem(bundle, source.copyWithCount(1))) continue;
            if (BundleRuleMatcher.matches(bundle, source)) return i;
        }
        return -1;
    }

    private static int findEmptySlot(Player player, int sourceIndex, int bundleIndex) {
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            if (i == sourceIndex || i == bundleIndex) continue;
            if (player.getInventory().getItem(i).isEmpty()) return i;
        }
        return -1;
    }

    private static Slot findSlot(Player player, int inventoryIndex) {
        for (Slot slot : player.containerMenu.slots) {
            if (slot.container == player.getInventory() && slot.getContainerSlot() == inventoryIndex) {
                return slot;
            }
        }
        return null;
    }

    /**
     * Use the normal client click path so the client predicts each step and the packet
     * carries the correct state id and carried-item hash. Hand-built packets used to claim
     * an empty carried stack, which could leave the cursor stale between clicks.
     */
    private static void click(Minecraft mc, Player player, int slot, int button) {
        if (mc.gameMode == null) return;
        mc.gameMode.handleContainerInput(
            player.containerMenu.containerId, slot, button, ContainerInput.PICKUP, player);
    }

    private static Map<Integer, ItemStack> snapshot(Player player) {
        Map<Integer, ItemStack> result = new HashMap<>();
        for (int i = 0; i < INVENTORY_SIZE; i++) {
            result.put(i, player.getInventory().getItem(i).copy());
        }
        return result;
    }

    private static Map<Integer, ItemStack> copy(Map<Integer, ItemStack> source) {
        Map<Integer, ItemStack> result = new HashMap<>();
        source.forEach((key, stack) -> result.put(key, stack.copy()));
        return result;
    }

    private static void reset() {
        tasks.clear();
        history.clear();
        pickupBaseline = null;
        pickupWindow = 0;
        cooldown = 0;
        suppress = 0;
        lastPlayer = null;
        lastLevel = null;
    }
}
