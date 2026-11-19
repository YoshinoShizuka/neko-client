// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.render;

import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.HitResult;

/** 填充状态 HUD 在批次 A 不启用；保留签名以维持引擎代码原样。 */
public final class QuickContainerFillStatus {
    private QuickContainerFillStatus() { }
    public static void initialize() { }
    public static void begin(Minecraft client, HitResult target) { }
    public static void progress(Minecraft client, HitResult target, int issues) { }
    public static void finish(Minecraft client, HitResult target, int issues) { }
    public static void stop(Minecraft client, HitResult target, boolean status) { }
    public static boolean sameTarget(Minecraft client, HitResult first, HitResult second) { return false; }
}
