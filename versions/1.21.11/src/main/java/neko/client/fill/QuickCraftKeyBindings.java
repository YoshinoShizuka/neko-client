// Derived from quickcraft v1.0.4 (MIT License, Copyright (c) 2026 yiyihehe).
// Optional branch stub: kept so the vendored fill engine compiles unchanged.
package neko.client.fill;

import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;

/** 长按判据：malilib 热键或与原版键完全一致的鼠标键；26.x 的 isKeybindHeld 不可信，故补原版状态。 */
public final class QuickCraftKeyBindings {
    private QuickCraftKeyBindings() { }

    public static boolean isVanillaKeyDown(Minecraft client, KeyMapping key) {
        return key != null && !key.isUnbound() && (key.isDown() || (client != null && key.isDown()));
    }

    public static boolean isHotkeyDown(Minecraft client, IKeybind keybind, KeyMapping vanillaFallback) {
        // ① 玩家热键回调驱动的开关：任意绑定键都能用（26.x 的 isKeybindHeld 不可信）
        if (ContinuousFillControl.isArmed()) {
            return true;
        }
        // ② 原有兼容：热键恰好绑成 keyUse 时，直接读原版按键状态
        if (keybind == null || vanillaFallback == null || vanillaFallback.isUnbound()) {
            return false;
        }
        if (keybind.matches(KeybindMulti.getKeyCode(vanillaFallback))) {
            return vanillaFallback.isDown();
        }
        return false;
    }
}
