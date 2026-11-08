package betterbundle.gui;

import betterbundle.BetterBundleConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * In-game settings screen, also exposed through Mod Menu. Every change is written to
 * better-bundle.json immediately; external edits to that file are hot-reloaded.
 */
public final class BetterBundleConfigScreen extends Screen {

    private static final int ROWS = 11;
    private static final int ROW_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 260;

    private final Screen parent;

    public BetterBundleConfigScreen(Screen parent) {
        super(Component.translatable("screen.better-bundle.config"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        BetterBundleConfig config = BetterBundleConfig.get();
        int x = this.width / 2 - BUTTON_WIDTH / 2;
        int y = Math.max(40, (this.height - ROWS * ROW_HEIGHT) / 2);

        addToggle(x, y, "screen.better-bundle.auto_deposit", config.autoDeposit,
            value -> config.autoDeposit = value);
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.auto_deposit_inventory", config.autoDepositInInventory,
            value -> config.autoDepositInInventory = value);
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.auto_deposit_menus", config.autoDepositInMenus,
            value -> config.autoDepositInMenus = value);
        y += ROW_HEIGHT;

        addButton(x, y, Component.translatable("screen.better-bundle.deposit_mode",
            depositModeName(config.depositMode)), () -> {
            config.depositMode = "WHOLE_STACK".equalsIgnoreCase(config.depositMode)
                ? "PICKED_UP_ONLY" : "WHOLE_STACK";
            BetterBundleConfig.save();
            rebuildWidgets();
        });
        y += ROW_HEIGHT;

        addButton(x, y, Component.translatable("screen.better-bundle.split_overflow",
            splitOverflowName(config.splitOverflow)), () -> {
            config.splitOverflow = "WHOLE_STACK".equalsIgnoreCase(config.splitOverflow)
                ? "SKIP" : "WHOLE_STACK";
            BetterBundleConfig.save();
            rebuildWidgets();
        });
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.allow_unmatched", config.allowUnmatchedManualInsert,
            value -> config.allowUnmatchedManualInsert = value);
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.match_item_id", config.matchItemId,
            value -> config.matchItemId = value);
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.match_display_name", config.matchDisplayName,
            value -> config.matchDisplayName = value);
        y += ROW_HEIGHT;

        addToggle(x, y, "screen.better-bundle.debug", config.debug,
            value -> config.debug = value);
        y += ROW_HEIGHT;

        addButton(x, y, Component.translatable("screen.better-bundle.reload"), () -> {
            boolean ok = BetterBundleConfig.reloadNow();
            Minecraft mc = Minecraft.getInstance();
            if (!ok && mc.player != null) {
                mc.player.sendOverlayMessage(
                    Component.translatable("message.better-bundle.config_reload_failed"));
            }
            rebuildWidgets();
        });
        y += ROW_HEIGHT;

        addButton(x, y, Component.translatable("screen.better-bundle.done"), this::onClose);
    }

    private void addToggle(int x, int y, String labelKey, boolean current, Consumer<Boolean> setter) {
        addButton(x, y, toggleLabel(labelKey, current), () -> {
            setter.accept(!current);
            BetterBundleConfig.save();
            rebuildWidgets();
        });
    }

    private void addButton(int x, int y, Component label, Runnable action) {
        addRenderableWidget(Button.builder(label, button -> action.run())
            .bounds(x, y, BUTTON_WIDTH, 18)
            .build());
    }

    private static Component toggleLabel(String labelKey, boolean value) {
        return Component.translatable(labelKey).append(": ").append(
            Component.translatable(value ? "screen.better-bundle.on" : "screen.better-bundle.off"));
    }

    private static Component depositModeName(String mode) {
        return Component.translatable("WHOLE_STACK".equalsIgnoreCase(mode)
            ? "screen.better-bundle.deposit_mode.whole_stack"
            : "screen.better-bundle.deposit_mode.picked_up_only");
    }

    private static Component splitOverflowName(String mode) {
        return Component.translatable("WHOLE_STACK".equalsIgnoreCase(mode)
            ? "screen.better-bundle.split_overflow.whole_stack"
            : "screen.better-bundle.split_overflow.skip");
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        graphics.text(this.font, this.title,
            this.width / 2 - this.font.width(this.title) / 2, 12, 0xFFFFFFFF, false);

        Component path = Component.translatable("screen.better-bundle.file",
            BetterBundleConfig.filePath().toString());
        graphics.text(this.font, path,
            Math.max(4, this.width / 2 - this.font.width(path) / 2), 26, 0xFFAAAAAA, false);
    }

    @Override
    public void onClose() {
        Minecraft.getInstance().gui.setScreen(this.parent);
    }
}
