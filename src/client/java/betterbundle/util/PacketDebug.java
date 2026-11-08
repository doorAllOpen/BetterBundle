package betterbundle.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.BundleContents;

/** Compact item/bundle descriptions for the packet debug log. */
public final class PacketDebug {

    private PacketDebug() {}

    public static String describe(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return "empty";
        StringBuilder sb = new StringBuilder();
        sb.append(BuiltInRegistries.ITEM.getKey(stack.getItem())).append('x').append(stack.getCount());

        BundleContents contents = stack.get(DataComponents.BUNDLE_CONTENTS);
        if (contents != null) {
            sb.append(" {");
            for (ItemStack item : contents.itemCopies().toList()) {
                sb.append(BuiltInRegistries.ITEM.getKey(item.getItem()))
                    .append('x').append(item.getCount()).append(',');
            }
            sb.append('}');
        }
        return sb.toString();
    }
}
