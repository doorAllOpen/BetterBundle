package betterbundle.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.BundleItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;
import org.apache.commons.lang3.math.Fraction;

public final class BundleContentsHelper {

    private BundleContentsHelper() {}

    public static BundleContents getContents(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        return stack.get(DataComponents.BUNDLE_CONTENTS);
    }

    public static boolean isNonEmptyBundle(ItemStack stack) {
        BundleContents contents = getContents(stack);
        return contents != null && !contents.isEmpty();
    }

    /** True if this stack is a Bundle item (even if empty/unused). */
    public static boolean isBundle(ItemStack stack) {
        return stack != null && !stack.isEmpty() && stack.getItem() instanceof BundleItem;
    }

    /** Check if the given item can still fit into this bundle. Works for empty bundles too. */
    public static boolean canFitItem(ItemStack bundleStack, ItemStack toInsert) {
        if (!isBundle(bundleStack) || toInsert.isEmpty()) return false;
        if (!BundleContents.canItemBeInBundle(toInsert)) return false;

        BundleContents contents = getContents(bundleStack);
        Fraction currentWeight;
        if (contents != null) {
            currentWeight = contents.weight().result().orElse(Fraction.ZERO);
        } else {
            currentWeight = Fraction.ZERO; // empty/unused bundle
        }

        Fraction itemWeight = Fraction.getFraction(toInsert.getCount(), toInsert.getMaxStackSize());
        return currentWeight.add(itemWeight).compareTo(Fraction.ONE) <= 0;
    }
}
