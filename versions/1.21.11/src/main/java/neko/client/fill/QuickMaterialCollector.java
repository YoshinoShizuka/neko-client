// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill;

import net.minecraft.client.Minecraft;

/** 材料自动收集在批次 A 不启用；缺料由仓库补货通道负责。 */
public final class QuickMaterialCollector {
    private QuickMaterialCollector() { }
    public static boolean shouldHandleCurrentTarget(Minecraft client) { return false; }
    public static boolean shouldSuppressBackgroundHandledScreenOpen() { return false; }
    public static boolean shouldSuppressUseInput() { return false; }
    public static boolean shouldHideBackgroundHandledScreen() { return false; }
}
