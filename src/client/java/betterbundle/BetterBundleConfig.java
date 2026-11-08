package betterbundle;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BetterBundleConfig {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("better-bundle.json");
    private static final int CURRENT_VERSION = 7;
    private static final int RELOAD_INTERVAL_TICKS = 20;
    private static BetterBundleConfig INSTANCE = new BetterBundleConfig();

    private static long lastModified = Long.MIN_VALUE;
    private static long lastSize = -1;
    private static int reloadCountdown = 0;

    /** Config schema version, used to migrate configs written by older builds. */
    public int configVersion = 0;

    /** Master switch for automatically moving picked-up items into a matching bundle. */
    public boolean autoDeposit = true;
    /** Keep auto-depositing while an inventory/container screen is open. Default: off. */
    public boolean autoDepositInInventory = false;
    /** Keep auto-depositing while the Esc menu, chat or another non-container screen is open. Default: on. */
    public boolean autoDepositInMenus = true;
    /**
     * Ticks to wait between sending the "select bundle item" packet and the extraction
     * click on a multiplayer server. Raise this if the server still takes the wrong item.
     */
    public int serverSelectDelayTicks = 8;
    /** Ticks between automated panel clicks on a multiplayer server. */
    public int serverStepDelayTicks = 4;
    /**
     * "SELECT": use the vanilla bundle selection packet (fast, works on most servers).
     * "ROTATE": rotate the wanted stack to the front with plain clicks. Slower and needs
     * empty inventory slots, but works on servers that ignore the selection packet.
     */
    public String extractionMode = "SELECT";
    /** Log outgoing/incoming inventory packets to the client log for debugging. */
    public boolean debugPackets = false;
    public int depositDelayTicks = 4;
    public int maxDepositsPerTick = 1;
    /**
     * "PICKED_UP_ONLY" only deposits the amount that was just picked up, leaving items
     * that were already in the stack alone. "WHOLE_STACK" deposits the entire merged stack.
     */
    public String depositMode = "PICKED_UP_ONLY";
    public int maxSplitItems = 16;
    /** What to do in PICKED_UP_ONLY mode when the picked-up amount cannot be split off: "SKIP" or "WHOLE_STACK". */
    public String splitOverflow = "SKIP";
    public int splitClickDelayTicks = 1;
    /**
     * When false (default), bundles with a matching rule refuse manual insertion of items
     * that do not match their rule. Bundles without a rule keep vanilla behaviour.
     */
    public boolean allowUnmatchedManualInsert = false;
    public String nameSeparator = "_";
    public boolean matchItemId = false;
    public boolean matchDisplayName = true;
    public boolean debug = false;

    public static BetterBundleConfig get() {
        return INSTANCE;
    }

    public static Path filePath() {
        return FILE;
    }

    public static void load() {
        if (Files.exists(FILE)) {
            readFromDisk();
        } else {
            INSTANCE.configVersion = CURRENT_VERSION;
            save();
        }
        rememberFileStamp();
    }

    /**
     * Re-reads the config file while the game is running. Edits made in an external editor
     * are applied within about a second; call is throttled by {@link #tickReload()}.
     * Returns 1 when applied, -1 when the file is unreadable/invalid, 0 when unchanged.
     */
    public static int tickReload() {
        if (++reloadCountdown < RELOAD_INTERVAL_TICKS) return 0;
        reloadCountdown = 0;
        if (!Files.exists(FILE)) return 0;

        try {
            long modified = Files.getLastModifiedTime(FILE).toMillis();
            long size = Files.size(FILE);
            if (modified == lastModified && size == lastSize) return 0;
        } catch (Exception e) {
            return 0;
        }
        return readFromDisk() ? 1 : -1;
    }

    /** Force an immediate re-read (used by the in-game config screen). */
    public static boolean reloadNow() {
        return readFromDisk();
    }

    private static boolean readFromDisk() {
        try {
            String json = Files.readString(FILE, StandardCharsets.UTF_8);
            // Windows editors may save UTF-8 with a BOM; Gson does not accept it.
            if (!json.isEmpty() && json.charAt(0) == '﻿') {
                json = json.substring(1);
            }
            BetterBundleConfig loaded = GSON.fromJson(json, BetterBundleConfig.class);
            if (loaded == null) {
                rememberFileStamp();
                return false;
            }
            if (loaded.nameSeparator == null || loaded.nameSeparator.isEmpty()) {
                loaded.nameSeparator = "_";
            }
            INSTANCE = loaded;
            if (loaded.configVersion < CURRENT_VERSION) {
                loaded.migrate();
            }
            rememberFileStamp();
            return true;
        } catch (Exception e) {
            // Keep the current in-memory config when the file is invalid or half-written.
            rememberFileStamp();
            return false;
        }
    }

    private static void rememberFileStamp() {
        try {
            lastModified = Files.getLastModifiedTime(FILE).toMillis();
            lastSize = Files.size(FILE);
        } catch (Exception e) {
            lastModified = Long.MIN_VALUE;
            lastSize = -1;
        }
    }

    /**
     * v1 configs were written before the picked-up-only mode existed and defaulted to
     * depositing the whole merged stack. Keep an explicit "PICKED_UP_ONLY" choice, but
     * turn the old default into the new one, and stop falling back to whole-stack deposits.
     * v3 added allowUnmatchedManualInsert; v4 added the two auto-deposit screen flags;
     * v5 added the server click-delay settings; v6 added extractionMode;
     * v7 added debugPackets. Missing fields use their initializers.
     */
    private void migrate() {
        if (configVersion < 2) {
            if (depositMode == null || "WHOLE_STACK".equalsIgnoreCase(depositMode)) {
                depositMode = "PICKED_UP_ONLY";
            }
            splitOverflow = "SKIP";
        }
        configVersion = CURRENT_VERSION;
        save();
    }

    public static void save() {
        try {
            Files.createDirectories(FILE.getParent());
            try (Writer writer = Files.newBufferedWriter(FILE, StandardCharsets.UTF_8)) {
                GSON.toJson(INSTANCE, writer);
            }
            // Our own write must not be mistaken for an external edit by the reload poller.
            rememberFileStamp();
        } catch (Exception ignored) {
        }
    }
}
