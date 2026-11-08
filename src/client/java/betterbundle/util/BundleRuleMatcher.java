package betterbundle.util;

import betterbundle.BetterBundleConfig;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Matches items against the "displayName<separator><regex>" naming convention used by
 * managed bundles. Shared by auto-deposit and by the manual-insertion guard.
 */
public final class BundleRuleMatcher {

    private static final Map<String, Optional<Pattern>> PATTERNS = new HashMap<>();

    private BundleRuleMatcher() {}

    /** The compiled rule of a bundle, or null when the name carries no usable rule. */
    public static Pattern ruleOf(ItemStack bundle) {
        if (bundle == null || bundle.isEmpty()) return null;
        Component customName = bundle.get(DataComponents.CUSTOM_NAME);
        if (customName == null) return null;
        String name = customName.getString();
        String separator = BetterBundleConfig.get().nameSeparator;
        int split = name.indexOf(separator);
        if (split <= 0 || split + separator.length() >= name.length()) return null;

        String regex = name.substring(split + separator.length()).trim();
        if (regex.isEmpty()) return null;

        return PATTERNS.computeIfAbsent(regex, key -> {
            try {
                return Optional.of(Pattern.compile(key, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE));
            } catch (PatternSyntaxException e) {
                return Optional.empty();
            }
        }).orElse(null);
    }

    /** True when this bundle is managed by a valid rule. Unmanaged bundles keep vanilla behaviour. */
    public static boolean hasRule(ItemStack bundle) {
        return ruleOf(bundle) != null;
    }

    public static boolean matches(ItemStack bundle, ItemStack item) {
        Pattern pattern = ruleOf(bundle);
        if (pattern == null || item == null || item.isEmpty()) return false;

        BetterBundleConfig config = BetterBundleConfig.get();
        if (config.matchItemId) {
            String id = BuiltInRegistries.ITEM.getKey(item.getItem()).toString();
            if (pattern.matcher(id).find()) return true;
        }
        if (config.matchDisplayName) {
            return pattern.matcher(item.getHoverName().getString()).find();
        }
        return false;
    }

    /**
     * True when a manual insert of {@code item} into {@code bundle} must be blocked.
     * Managed bundles only accept matching items unless the player explicitly allows
     * unmatched items in the config.
     */
    public static boolean blocksManualInsert(ItemStack bundle, ItemStack item) {
        if (BetterBundleConfig.get().allowUnmatchedManualInsert) return false;
        if (!hasRule(bundle)) return false;
        return !matches(bundle, item);
    }
}
