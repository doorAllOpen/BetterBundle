package betterbundle.gui;

import betterbundle.util.BundleContentsHelper;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.AbstractRecipeBookScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import net.sourceforge.pinyin4j.PinyinHelper;
import net.sourceforge.pinyin4j.format.HanyuPinyinOutputFormat;
import net.sourceforge.pinyin4j.format.HanyuPinyinToneType;
import org.apache.commons.lang3.math.Fraction;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Side panel grouped by bundle: the left column selects a bundle, the grid shows only
 * that bundle's contents. This replaces the old "all bundles merged into one grid" view.
 */
public final class BundlePanelRenderer {

    public static final int SLOT_SIZE = 18;
    public static final int COLUMNS = 5;
    public static final int VISIBLE_ROWS = 8;
    public static final int SLOT_SPACING = 0;
    public static final int PADDING = 3;
    public static final int SCROLL_BAR_WIDTH = 4;
    public static final int BUNDLE_CELL = 20;
    public static final int BUNDLE_BAR_WIDTH = 22;
    public static final int SEARCH_BAR_HEIGHT = 14;

    private static int scrollOffset = 0;
    private static int bundleScroll = 0;
    public static boolean visible = true;

    public static String searchQuery = "";
    public static boolean searchFocused = false;
    private static int searchCursorTick = 0;
    private static int hoveredBundleSlot = -1;
    private static int selectedBundleSlot = -1;
    private static AbstractContainerMenu lastMenu;

    private BundlePanelRenderer() {}

    /** bundleSlot = container slot index (for clickSlot packets) */
    public record BundleSlotEntry(int bundleSlot, ItemStack bundleStack, BundleContents contents) {}

    public record FlatItem(int bundleSlot, int itemIndex, ItemStack stack) {}

    public static int panelWidth() {
        return BUNDLE_BAR_WIDTH + 2 + SCROLL_BAR_WIDTH + 2
                + COLUMNS * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING + PADDING * 2;
    }

    public static int panelHeight(int imageHeight) {
        return Math.min(imageHeight, searchHeight() + gridHeight()) + 34;
    }

    private static int searchHeight() {
        return SEARCH_BAR_HEIGHT + 3;
    }

    private static int gridHeight() {
        return PADDING * 2 + VISIBLE_ROWS * SLOT_SIZE + (VISIBLE_ROWS - 1) * SLOT_SPACING;
    }

    private static int panelX(int leftPos) {
        return leftPos - panelWidth() - 4;
    }

    public static int gridX(int leftPos) {
        return panelX(leftPos) + PADDING + BUNDLE_BAR_WIDTH + 2 + SCROLL_BAR_WIDTH + 2;
    }

    public static int gridY(int topPos) {
        return topPos + searchHeight() + PADDING;
    }

    private static int searchX(int leftPos) {
        return panelX(leftPos) + PADDING + BUNDLE_BAR_WIDTH + 2;
    }

    private static int searchY(int topPos) {
        return topPos + 2;
    }

    private static int searchWidth() {
        return panelWidth() - PADDING - BUNDLE_BAR_WIDTH - 2 - PADDING - 10;
    }

    private static int bundleBarX(int leftPos) {
        return panelX(leftPos) + PADDING;
    }

    private static int bundleCellY(int topPos, int index) {
        return topPos + 2 + index * BUNDLE_CELL;
    }

    private static int visibleBundleCells(int imageHeight) {
        return Math.max(1, (panelHeight(imageHeight) - 4) / BUNDLE_CELL);
    }

    // --- state ---

    public static int getScrollOffset() { return scrollOffset; }

    public static void scrollItems(int delta) {
        List<FlatItem> items = getSelectedItems();
        int totalRows = Math.max(1, (items.size() + COLUMNS - 1) / COLUMNS);
        int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
        scrollOffset = Math.clamp(scrollOffset + delta, 0, maxScroll);
    }

    public static void scrollBundles(int delta, int imageHeight) {
        List<BundleSlotEntry> bundles = getAllBundles();
        int maxScroll = Math.max(0, bundles.size() - visibleBundleCells(imageHeight));
        bundleScroll = Math.clamp(bundleScroll + delta, 0, maxScroll);
    }

    public static void selectBundle(int bundleSlot) {
        if (selectedBundleSlot != bundleSlot) {
            selectedBundleSlot = bundleSlot;
            scrollOffset = 0;
        }
    }

    public static BundleSlotEntry getSelectedBundle() {
        List<BundleSlotEntry> bundles = getAllBundles();
        if (bundles.isEmpty()) {
            selectedBundleSlot = -1;
            return null;
        }
        for (BundleSlotEntry entry : bundles) {
            if (entry.bundleSlot() == selectedBundleSlot) return entry;
        }
        // Default to the first non-empty bundle so the panel is useful immediately.
        List<BundleSlotEntry> nonEmpty = getBundles();
        BundleSlotEntry fallback = nonEmpty.isEmpty() ? bundles.get(0) : nonEmpty.get(0);
        selectedBundleSlot = fallback.bundleSlot();
        return fallback;
    }

    /** Items of the selected bundle, filtered by the search query. */
    public static List<FlatItem> getSelectedItems() {
        BundleSlotEntry selected = getSelectedBundle();
        if (selected == null) return List.of();
        return filterItems(buildFlatItemList(List.of(selected)), searchQuery);
    }

    // --- bundles / items ---

    public static List<BundleSlotEntry> getBundles() { return findBundles(false); }
    public static List<BundleSlotEntry> getAllBundles() { return findBundles(true); }

    private static List<BundleSlotEntry> findBundles(boolean includeEmpty) {
        Minecraft client = Minecraft.getInstance();
        Player player = client.player;
        if (player == null) return List.of();
        checkMenu(player);
        List<BundleSlotEntry> result = new ArrayList<>();
        Inventory inv = player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack stack = inv.getItem(i);
            boolean matches = includeEmpty
                    ? BundleContentsHelper.isBundle(stack)
                    : BundleContentsHelper.isNonEmptyBundle(stack);
            if (matches) {
                int containerSlot = findContainerSlot(player, inv, i);
                result.add(new BundleSlotEntry(containerSlot, stack, BundleContentsHelper.getContents(stack)));
            }
        }
        return result;
    }

    /** Convert player inventory index (0-35) to container menu slot index. */
    private static int findContainerSlot(Player player, Inventory inv, int inventoryIndex) {
        for (net.minecraft.world.inventory.Slot slot : player.containerMenu.slots) {
            if (slot.container == inv && slot.getContainerSlot() == inventoryIndex) {
                return slot.index;
            }
        }
        return inventoryIndex;
    }

    public static List<FlatItem> buildFlatItemList(List<BundleSlotEntry> bundles) {
        List<FlatItem> result = new ArrayList<>();
        for (BundleSlotEntry entry : bundles) {
            if (entry.contents() == null) continue;
            List<ItemStack> items = entry.contents().itemCopies().toList();
            for (int i = 0; i < items.size(); i++) {
                result.add(new FlatItem(entry.bundleSlot(), i, items.get(i)));
            }
        }
        return result;
    }

    public static List<FlatItem> filterItems(List<FlatItem> items, String query) {
        if (query.isEmpty()) return items;
        String q = query.toLowerCase(Locale.ROOT);
        List<FlatItem> filtered = new ArrayList<>();
        for (FlatItem fi : items) {
            if (matchesSearch(fi, q)) filtered.add(fi);
        }
        return filtered;
    }

    private static boolean matchesSearch(FlatItem fi, String q) {
        String name = fi.stack().getDisplayName().getString().toLowerCase(Locale.ROOT);
        if (name.contains(q)) return true;
        if (toPinyin(name).contains(q)) return true;
        var key = BuiltInRegistries.ITEM.getKey(fi.stack().getItem());
        String fullId = key.toString().toLowerCase(Locale.ROOT);
        String path = key.getPath().toLowerCase(Locale.ROOT);
        return fullId.contains(q) || path.contains(q);
    }

    private static String toPinyin(String text) {
        try {
            HanyuPinyinOutputFormat fmt = new HanyuPinyinOutputFormat();
            fmt.setToneType(HanyuPinyinToneType.WITHOUT_TONE);
            StringBuilder sb = new StringBuilder();
            for (char c : text.toCharArray()) {
                String[] arr = PinyinHelper.toHanyuPinyinStringArray(c, fmt);
                if (arr != null && arr.length > 0) sb.append(arr[0]);
            }
            return sb.toString().toLowerCase(Locale.ROOT);
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * Slot indexes are only valid for one menu instance. When the player switches from a
     * chest (player slots are 27..62) to the inventory (9..44), any cached index can be
     * out of bounds; reset the selection and scroll state.
     */
    private static void checkMenu(Player player) {
        AbstractContainerMenu menu = player.containerMenu;
        if (menu != lastMenu) {
            lastMenu = menu;
            selectedBundleSlot = -1;
            hoveredBundleSlot = -1;
            scrollOffset = 0;
            bundleScroll = 0;
        }
    }

    public static boolean isRecipeBookOpen() {
        Minecraft client = Minecraft.getInstance();
        if (client.gui.screen() instanceof AbstractRecipeBookScreen<?> screen) {
            return screen.recipeBookComponent.isVisible();
        }
        return false;
    }

    public static int getHoveredBundleSlot() {
        if (!visible) return -1;
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return -1;
        int slot = hoveredBundleSlot;
        if (slot < 0 || slot >= client.player.containerMenu.slots.size()) return -1;
        if (!BundleContentsHelper.isBundle(client.player.containerMenu.slots.get(slot).getItem())) {
            return -1;
        }
        return slot;
    }

    public static boolean isEffectivelyVisible() { return visible && !isRecipeBookOpen(); }
    public static void toggleVisible() { visible = !visible; }

    // --- hit testing ---

    /** Returns the bundle slot under the mouse in the selector column, or -1. */
    public static int getBundleAt(double mouseX, double mouseY, int leftPos, int topPos, int imageHeight) {
        if (!isEffectivelyVisible()) return -1;
        List<BundleSlotEntry> bundles = getAllBundles();
        if (bundles.isEmpty()) return -1;
        int cells = visibleBundleCells(imageHeight);
        int barX = bundleBarX(leftPos);
        for (int i = 0; i < cells && i + bundleScroll < bundles.size(); i++) {
            int cy = bundleCellY(topPos, i);
            if (mouseX >= barX && mouseX < barX + BUNDLE_CELL
                    && mouseY >= cy && mouseY < cy + BUNDLE_CELL) {
                return bundles.get(i + bundleScroll).bundleSlot();
            }
        }
        return -1;
    }

    public static boolean isInsideBundleBar(double mouseX, double mouseY, int leftPos, int topPos, int imageHeight) {
        int barX = bundleBarX(leftPos);
        return mouseX >= barX && mouseX < barX + BUNDLE_BAR_WIDTH
                && mouseY >= topPos && mouseY < topPos + panelHeight(imageHeight);
    }

    public static boolean isInsideSearchBar(double mouseX, double mouseY, int leftPos, int topPos) {
        int sx = searchX(leftPos);
        int sy = searchY(topPos);
        return mouseX >= sx && mouseX <= sx + searchWidth()
                && mouseY >= sy && mouseY <= sy + SEARCH_BAR_HEIGHT;
    }

    public static void onCharTyped(char c) {
        if (!searchFocused) return;
        if (c >= 32 && c != 127) {
            searchQuery += c;
            scrollOffset = 0;
        }
    }

    public static void onSearchKeyPress(int key) {
        if (!searchFocused) return;
        if (key == 259) {
            if (!searchQuery.isEmpty()) {
                searchQuery = searchQuery.substring(0, searchQuery.length() - 1);
                scrollOffset = 0;
            }
        } else if (key == 256) {
            searchQuery = "";
            searchFocused = false;
            scrollOffset = 0;
        } else if (key == 257 || key == 335) {
            searchFocused = false;
        }
    }

    // --- render ---

    public static void render(GuiGraphicsExtractor graphics, int leftPos, int topPos,
                              int imageHeight, int mouseX, int mouseY) {
        if (!isEffectivelyVisible()) return;

        Minecraft client = Minecraft.getInstance();
        Font font = client.font;

        int pw = panelWidth();
        int px = panelX(leftPos);
        int height = panelHeight(imageHeight);
        int searchH = searchHeight();

        // Vanilla-style panel: light gray body with a bevel, same as container GUIs.
        graphics.fill(px, topPos, px + pw, topPos + height, 0xFFC6C6C6);
        graphics.fill(px, topPos, px + pw, topPos + 1, 0xFFFFFFFF);
        graphics.fill(px, topPos, px + 1, topPos + height, 0xFFFFFFFF);
        graphics.fill(px, topPos + height - 1, px + pw, topPos + height, 0xFF373737);
        graphics.fill(px + pw - 1, topPos, px + pw, topPos + height, 0xFF373737);

        List<BundleSlotEntry> bundles = getAllBundles();
        if (bundles.isEmpty()) {
            hoveredBundleSlot = -1;
            graphics.text(font, Component.translatable("screen.better-bundle.no_bundles"),
                px + PADDING + BUNDLE_BAR_WIDTH + 2, topPos + 4, 0xFF404040, false);
            return;
        }

        BundleSlotEntry selected = getSelectedBundle();
        if (selected == null) {
            hoveredBundleSlot = -1;
            return;
        }

        // --- bundle selector column ---
        int barX = bundleBarX(leftPos);
        int cells = visibleBundleCells(imageHeight);
        int maxBundleScroll = Math.max(0, bundles.size() - cells);
        if (bundleScroll > maxBundleScroll) bundleScroll = maxBundleScroll;

        for (int i = 0; i < cells && i + bundleScroll < bundles.size(); i++) {
            BundleSlotEntry entry = bundles.get(i + bundleScroll);
            int cy = bundleCellY(topPos, i);
            boolean isSelected = entry.bundleSlot() == selected.bundleSlot();
            boolean isHovered = mouseX >= barX && mouseX < barX + BUNDLE_CELL
                    && mouseY >= cy && mouseY < cy + BUNDLE_CELL;

            drawSlotFrame(graphics, barX, cy);
            if (isHovered) {
                graphics.fill(barX, cy, barX + SLOT_SIZE, cy + SLOT_SIZE, 0x60FFFFFF);
            }
            if (isSelected) {
                graphics.fill(barX - 1, cy - 1, barX + SLOT_SIZE + 1, cy, 0xFFFFD700);
                graphics.fill(barX - 1, cy + SLOT_SIZE, barX + SLOT_SIZE + 1, cy + SLOT_SIZE + 1, 0xFFFFD700);
                graphics.fill(barX - 1, cy, barX, cy + SLOT_SIZE, 0xFFFFD700);
                graphics.fill(barX + SLOT_SIZE, cy, barX + SLOT_SIZE + 1, cy + SLOT_SIZE, 0xFFFFD700);
            }
            graphics.item(entry.bundleStack(), barX + 1, cy + 1);
            graphics.itemDecorations(font, entry.bundleStack(), barX + 1, cy + 1);

            if (isHovered) {
                graphics.setTooltipForNextFrame(font, entry.bundleStack(), mouseX, mouseY);
            }
        }
        hoveredBundleSlot = selected.bundleSlot();

        // --- item grid ---
        List<FlatItem> items = getSelectedItems();
        int totalRows = Math.max(1, (items.size() + COLUMNS - 1) / COLUMNS);
        int maxScroll = Math.max(0, totalRows - VISIBLE_ROWS);
        if (scrollOffset > maxScroll) scrollOffset = maxScroll;

        int sbX = px + PADDING + BUNDLE_BAR_WIDTH + 2;
        int gridTop = topPos + searchH;
        int gridContentH = height - searchH;
        int sbY = gridTop + PADDING;
        int sbH = gridContentH - PADDING * 2;

        graphics.fill(sbX, sbY, sbX + SCROLL_BAR_WIDTH, sbY + sbH, 0xFF8B8B8B);
        if (maxScroll > 0) {
            int thumbH = Math.max(12, sbH * VISIBLE_ROWS / totalRows);
            int thumbY = sbY + (sbH - thumbH) * scrollOffset / maxScroll;
            graphics.fill(sbX, thumbY, sbX + SCROLL_BAR_WIDTH, thumbY + thumbH, 0xFFC6C6C6);
            graphics.fill(sbX, thumbY, sbX + SCROLL_BAR_WIDTH, thumbY + 1, 0xFFFFFFFF);
            graphics.fill(sbX, thumbY + thumbH - 1, sbX + SCROLL_BAR_WIDTH, thumbY + thumbH, 0xFF373737);
        }

        int gx = gridX(leftPos);
        int gy = gridY(topPos);
        int startRow = scrollOffset;
        int hoveredFlatIndex = -1;

        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLUMNS; col++) {
                int sx = gx + col * (SLOT_SIZE + SLOT_SPACING);
                int sy = gy + row * (SLOT_SIZE + SLOT_SPACING);
                drawSlotFrame(graphics, sx, sy);

                int flatIndex = (startRow + row) * COLUMNS + col;
                if (flatIndex >= items.size()) continue;

                FlatItem fi = items.get(flatIndex);
                graphics.item(fi.stack(), sx + 1, sy + 1);
                graphics.itemDecorations(client.font, fi.stack(), sx + 1, sy + 1);

                if (mouseX >= sx && mouseX < sx + SLOT_SIZE
                        && mouseY >= sy && mouseY < sy + SLOT_SIZE) {
                    hoveredFlatIndex = flatIndex;
                }
            }
        }

        if (hoveredFlatIndex >= 0) {
            int hRow = hoveredFlatIndex / COLUMNS - startRow;
            int hCol = hoveredFlatIndex % COLUMNS;
            int hx = gx + hCol * (SLOT_SIZE + SLOT_SPACING);
            int hy = gy + hRow * (SLOT_SIZE + SLOT_SPACING);
            graphics.fill(hx, hy, hx + SLOT_SIZE, hy + SLOT_SIZE, 0x80FFFFFF);
            graphics.setTooltipForNextFrame(client.font, items.get(hoveredFlatIndex).stack(), mouseX, mouseY);
        }

        // --- search bar ---
        int sx = searchX(leftPos);
        int sy = searchY(topPos);
        int sw = searchWidth();
        // Sunken vanilla-style text box
        graphics.fill(sx, sy, sx + sw, sy + SEARCH_BAR_HEIGHT, 0xFF373737);
        graphics.fill(sx, sy, sx + sw, sy + 1, 0xFF000000);
        graphics.fill(sx, sy, sx + 1, sy + SEARCH_BAR_HEIGHT, 0xFF000000);
        graphics.fill(sx, sy + SEARCH_BAR_HEIGHT - 1, sx + sw, sy + SEARCH_BAR_HEIGHT, 0xFFFFFFFF);
        graphics.fill(sx + sw - 1, sy, sx + sw, sy + SEARCH_BAR_HEIGHT, 0xFFFFFFFF);
        int textY = sy + (SEARCH_BAR_HEIGHT - font.lineHeight) / 2;
        if (searchQuery.isEmpty() && !searchFocused) {
            graphics.text(font, Component.translatable("screen.better-bundle.search"),
                sx + 3, textY, 0xFF707070, false);
        } else if (!searchQuery.isEmpty()) {
            graphics.text(font, searchQuery, sx + 3, textY, 0xFFFFFFFF, false);
            searchCursorTick = (searchCursorTick + 1) % 40;
            if (searchFocused && searchCursorTick < 20) {
                int cursorX = sx + 3 + font.width(searchQuery);
                graphics.fill(cursorX, textY, cursorX + 1, textY + font.lineHeight, 0xFFFFFFFF);
            }
        }

        // --- selected bundle capacity ---
        int[] stats = getSelectedStats(selected);
        String countText = stats[0] + "/" + stats[1];
        int textW = font.width(countText);
        int countX = gx + COLUMNS * (SLOT_SIZE + SLOT_SPACING) - SLOT_SPACING - textW;
        int countY = gy + VISIBLE_ROWS * SLOT_SIZE + (VISIBLE_ROWS - 1) * SLOT_SPACING + 7;
        graphics.text(font, countText, countX, countY, 0xFF404040, false);

        // Control hint, so left/right behaviour is obvious.
        Player hintPlayer = Minecraft.getInstance().player;
        boolean holding = hintPlayer != null && !hintPlayer.containerMenu.getCarried().isEmpty();
        graphics.text(font, Component.translatable(holding
                ? "screen.better-bundle.hint.put" : "screen.better-bundle.hint.take"),
            px + PADDING, topPos + height - 11, 0xFF404040, false);
    }

    /** Vanilla slot frame from the inventory texture, so resource packs change it too. */
    private static void drawSlotFrame(GuiGraphicsExtractor graphics, int x, int y) {
        graphics.blit(RenderPipelines.GUI_TEXTURED, AbstractContainerScreen.INVENTORY_LOCATION,
            x, y, 8f, 84f, SLOT_SIZE, SLOT_SIZE, 256, 256);
    }

    private static int[] getSelectedStats(BundleSlotEntry entry) {
        BundleContents contents = entry.contents();
        if (contents == null || contents.isEmpty()) return new int[] {0, 64};
        int totalItems = contents.itemCopies().mapToInt(ItemStack::getCount).sum();
        Fraction weight = contents.weight().result().orElse(Fraction.ZERO);
        Fraction remaining = Fraction.ONE.subtract(weight);
        int effectiveMax = totalItems + remaining.multiplyBy(Fraction.getFraction(64, 1)).intValue();
        return new int[] {totalItems, effectiveMax};
    }
}
