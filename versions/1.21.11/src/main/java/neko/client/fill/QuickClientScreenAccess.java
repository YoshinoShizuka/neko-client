package neko.client.fill;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

/** 1.21.11 的 screen 是公开字段，不需要 26.x 那套 SDL 反射探测。 */
public final class QuickClientScreenAccess {
    private QuickClientScreenAccess() { }
    public static Screen currentScreen(Minecraft client) {
        return client != null ? client.screen : null;
    }
}
