// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill;

import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;

/** 容器锁分支在批次 A 不启用：所有槽位都视为未锁。 */
public final class QuickContainerLock {
    private QuickContainerLock() { }
    public static boolean isLockedSlot(Slot slot) { return false; }
    public static boolean isLockedSlot(AbstractContainerMenu handler, int slotId) { return false; }
    public static boolean isLockedSlot(AbstractContainerMenu handler, Slot slot) { return false; }
}
