package neko.voidwarehouse.search;

import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 平台差异收口：<b>只有这份文件在五个版本之间不同</b>（26.x 那四份逐字节相同，见那边的注释）。
 *
 * <h2>1.21.11 侧的两处分叉</h2>
 * <ul>
 *   <li><b>注册按键</b>：还是老的 {@code fabric-key-binding-api-v1} ——
 *       {@code net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper#registerKeyBinding}。
 *       26.x 把模块改成了 {@code fabric-key-mapping-api-v1}、
 *       类改成 {@code KeyMappingHelper#registerKeyMapping}（跟着 MC 的
 *       {@code KeyBinding → KeyMapping} 一起改名），两边不能共享一份源码。</li>
 *   <li><b>动作栏消息</b>：{@code LocalPlayer#displayClientMessage(Component, true)}。
 *       26.3 里这个方法没了，换成了 {@code sendSystemMessage(Component)}
 *       （内部同样是 {@code ChatListener#handleSystemMessage(c, true)}，overlay 写死 true）。</li>
 * </ul>
 */
public final class SearchPlatform {

    private SearchPlatform() {
    }

    /** 把 {@link KeyMapping} 注册进原版的按键绑定表（之后玩家能在「控制 → 按键绑定」里改、存档） */
    public static void registerKeyMapping(KeyMapping mapping) {
        KeyBindingHelper.registerKeyBinding(mapping);
    }

    /** 在动作栏（物品栏上方那行，不进聊天记录）提示一句 */
    public static void showActionBar(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.displayClientMessage(message, true);
        }
    }
}
