// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.config;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import net.fabricmc.loader.api.FabricLoader;
import neko.client.SearchConfig;
import java.util.List;

/** 配置桥：核心填充路径只读这几项，全部落在 neko-client 自己的开关上。 */
public final class QuickCraftConfigs {
    private QuickCraftConfigs() { }

    private static boolean enabled() {
        return SearchConfig.isProjectionFillEnabled() && !FabricLoader.getInstance().isModLoaded("quickcraft");
    }

    public static boolean isQuickContainerCopyEnabled() { return enabled(); }
    public static boolean isLitematicaContainerAutofillEnabled() { return enabled(); }
    public static boolean isLitematicaContainerAutofillWithQuickShulkerEnabled() { return false; }
    public static boolean isCreativeContainerFillEnabled() { return false; }
    public static boolean isContainerFillOverflowDropEnabled() { return false; }
    public static int getContainerFillFreeSlotsLimit() { return 0; }
    public static List<String> getContainerFillProtectedItems() { return List.of(); }
    public static int getQuickShulkerActionIntervalTicks() { return 0; }
    public static void saveToFile() { SearchConfig.saveToFile(); }

    public static final class Hotkeys {
        public static final ConfigHotkey CONTINUOUS_CONTAINER_FILL = SearchConfig.CONTINUOUS_FILL;
        private Hotkeys() { }
    }

}