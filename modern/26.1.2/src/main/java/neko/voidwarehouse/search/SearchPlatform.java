package neko.voidwarehouse.search;

import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;

/**
 * 平台差异收口：<b>只有这份文件在五个版本之间不同</b>（26.x 四份逐字节相同）。
 *
 * <h2>两处分叉</h2>
 * <table border="1">
 *   <tr><th></th><th>26.x（本文件）</th><th>1.21.11</th></tr>
 *   <tr><td>注册按键</td>
 *       <td>{@code net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper#registerKeyMapping}</td>
 *       <td>{@code ...client.keybinding.v1.KeyBindingHelper#registerKeyBinding}</td></tr>
 *   <tr><td>动作栏消息</td>
 *       <td>{@code LocalPlayer#sendSystemMessage(Component)}（26.3 里它内部就是
 *           {@code ChatListener#handleSystemMessage(c, true)}，overlay 写死 true）</td>
 *       <td>{@code LocalPlayer#displayClientMessage(Component, true)}</td></tr>
 * </table>
 *
 * <p>🔴 <b>26.x 把 Fabric 的 {@code fabric-key-binding-api-v1} 改名成了
 * {@code fabric-key-mapping-api-v1}</b>（跟着 MC 的 {@code KeyBinding → KeyMapping} 一起改），
 * 类名也从 {@code KeyBindingHelper} 变成 {@code KeyMappingHelper} —— 两边的
 * fabric-api 模块名、包名、类名全不一样，不能共享一份源码。
 * 判据：{@code fabric-api-0.161.0+26.3.jar} 内嵌的是
 * {@code fabric-key-mapping-api-v1-2.0.8}，而 {@code 0.140.2+1.21.11} 内嵌的是
 * {@code fabric-key-binding-api-v1-1.1.7}。</p>
 */
public final class SearchPlatform {

    private SearchPlatform() {
    }

    /** 把 {@link KeyMapping} 注册进原版的按键绑定表（之后玩家能在「控制 → 按键绑定」里改、存档） */
    public static void registerKeyMapping(KeyMapping mapping) {
        KeyMappingHelper.registerKeyMapping(mapping);
    }

    /** 在动作栏（物品栏上方那行，不进聊天记录）提示一句 */
    public static void showActionBar(Component message) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null) {
            player.sendSystemMessage(message);
        }
    }
}
