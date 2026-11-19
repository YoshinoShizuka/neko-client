package neko.client.mixin;

import net.minecraft.network.protocol.game.ClientboundSystemChatPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 「聊天静音」开关的落点：系统聊天包入队前，按 {@link VoidWarehouseChatFilter} 判据丢包。
 *
 * <h2>为什么在 HEAD cancel，而不是渲染时隐藏</h2>
 * <p>渲染时隐藏得先知道「哪一行是这个 mod 发的」，而原版聊天组件
 * （{@code GuiMessages}）只存 {@code Component}，来源信息早就不在了。
 * 在收到包的那一刻判断，是唯一同时拿得到<b>原文</b>和<b>这是系统消息</b>的位置：
 * {@code handleSystemChat} 只处理系统聊天，玩家互相说话走的是另一条
 * （{@code handleChat} / {@code handleDisguisedChat}），不会被碰到。</p>
 *
 * <h2>🔴 判据本体不在本类</h2>
 * <p>这里只有「开关开着 ⇒ 问一句判据」这一层薄壳。判据在
 * {@link VoidWarehouseChatFilter}，纯文本、无 MC 依赖，JUnit 直接跑。
 * 判据写进 mixin 的后果 0.4.3 已经演过一次：单测够不着，语义错了只能靠真机日志。</p>
 *
 * <h2>作用域</h2>
 * <p>只影响<b>这一台客户端</b>的显示：服务端照常发、别人照常看得到、
 * 已经留在聊天栏里的历史消息也不会被清掉。关掉开关后新消息立刻恢复显示。</p>
 *
 * <h2>五版本 API 实证</h2>
 * <p>26.3 / 26.2 / 26.1.2 / 26.1.1 / 1.21.11 逐版 javap：
 * {@code ClientPacketListener#handleSystemChat(ClientboundSystemChatPacket)}，
 * 包里有 {@code Component content()} 与 {@code boolean overlay()}。
 * 因此本文件与其余源码一样可以五版本逐字节共享。</p>
 */
@Mixin(net.minecraft.client.multiplayer.ClientPacketListener.class)
public class MixinClientPacketListener {

    @Inject(method = "handleSystemChat", at = @At("HEAD"), cancellable = true)
    private void voidwarehouseSearch$onSystemChat(ClientboundSystemChatPacket packet, CallbackInfo ci) {
        if (!neko.client.SearchConfig.isChatSilenced()) {
            return;
        }
        String text = packet.content().getString();
        if (neko.client.VoidWarehouseChatFilter.shouldSilence(text)) {
            ci.cancel();
        }
    }
}
