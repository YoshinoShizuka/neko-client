// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill.compat;

/** Quick Shulker 桥接在批次 A 不启用：一律报告不可用。 */
public final class QuickShulkerConnection {
    private QuickShulkerConnection() { }
    public static boolean canBundle() { return false; }
    public static boolean canOpen() { return false; }
    public static boolean sendOpen(int slotId) { return false; }
}
