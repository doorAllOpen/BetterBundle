package betterbundle;

import betterbundle.gui.BetterBundleConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** Adds the "config" button to this mod's page in Mod Menu. */
public final class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> new BetterBundleConfigScreen(parent);
    }
}
