// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.config;

import neko.client.SearchConfig;
import java.util.List;

/** 配置桥：核心填充路径只读这几项，全部落在 neko-client 自己的开关上。 */
public final class QuickCraftConfigs {
    private QuickCraftConfigs() { }

    /**
     * 🔴 不再因为 quickcraft 也在场就把自己关掉。
     *
     * <p>0.5.4 初版在这里写了 {@code !isModLoaded("quickcraft")}，玩家实测
     * 「两个一起装时 neko-client 的填充完全没反应」——共存是玩家自己的选择，
     * 模组应当各读各的开关，而不是替玩家决定谁让位。入口仍会 warn 提示可能
     * 双份响应右键，但绝不静默关功能。</p>
     */
    private static boolean enabled() {
        return SearchConfig.isProjectionFillEnabled();
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

}