package neko.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 抢回「同一个 S2C 通道的接收器」：Fabric 的规则是<b>先注册者赢</b>，
 * 后注册的人只会拿到 {@code false}，而且<b>拿不到先前那个 handler</b>。
 *
 * <h2>为什么必须接管</h2>
 * <p>{@code ClientPlayNetworking#registerGlobalReceiver} 的 javadoc 写得很直白：
 * 「If a handler is already registered for the {@code type}, this method will return {@code false},
 * and <b>no change will be made</b>」。而模组的初始化顺序由 fabric-loader 的
 * {@code ModPrioSorter} 决定 —— 它最后一条判据是 {@code getId().compareTo(getId())}，
 * 也就是<b>按 mod id 字典序</b>。{@code litematica-printer} &lt; {@code neko_client}，
 * 所以 printer 的 {@code onInitializeClient} 永远先跑，{@code printer_take_result} /
 * {@code printer_return_result} 两条回执通道永远是它先占住。</p>
 *
 * <p>0.5.7 真机日志就是这么断的：服务端明明取走了 64 个（聊天栏有「取走 64 个 铁锭」），
 * 客户端却打 {@code nonce=2 超时无回执} ⇒ 判定「一件没到货」⇒ 放弃续填，
 * 玩家看到的就是「第一次右键只取货、容器一点没填」。</p>
 *
 * <h2>为什么是「接管 + 转发」而不是「换个通道名」</h2>
 * <p>换名字不解决任何问题：{@code printer_return_result} 这个 id 是<b>服务端</b>定的，
 * 换名等于收不到。接管则是把先前那个 handler 取下来，换成「我们的 + 他们的」链式版本 ——
 * 两边都能继续工作，谁先初始化都无所谓。</p>
 *
 * <p>{@code unregisterGlobalReceiver} 五版本（fabric-api 5.1.x / 6.3.x）签名一致：
 * {@code PlayPayloadHandler<?> unregisterGlobalReceiver(Identifier)}，javap 已核对。</p>
 */
public final class PayloadReceivers {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    private PayloadReceivers() {
    }

    /**
     * 接管一条 S2C 通道的全局接收器，并把先前那个一并转发。
     *
     * @param type 通道
     * @param ours 本模组的处理逻辑（先收）
     * @return {@code true} = 之前已被别的模组占用（已接管并转发）；{@code false} = 我们是第一个
     */
    @SuppressWarnings("unchecked")
    public static <T extends CustomPacketPayload> boolean attach(
            CustomPacketPayload.Type<T> type, ClientPlayNetworking.PlayPayloadHandler<T> ours) {
        ClientPlayNetworking.PlayPayloadHandler<?> previous = null;
        try {
            previous = ClientPlayNetworking.unregisterGlobalReceiver(type.id());
        } catch (Throwable unreadable) {
            // 取不下来（API 变了 / 还没注册过）⇒ 直接注册，最坏退化成「只有我们收」
            LOGGER.info("[neko-client] 通道 {} 的既有接收器取不下来（{}），直接注册",
                    type.id(), unreadable.toString());
        }
        final ClientPlayNetworking.PlayPayloadHandler<T> chained =
                previous == null ? null : (ClientPlayNetworking.PlayPayloadHandler<T>) previous;
        ClientPlayNetworking.registerGlobalReceiver(type, (payload, context) -> {
            try {
                ours.receive(payload, context);
            } catch (Throwable broken) {
                LOGGER.warn("[neko-client] 通道 {} 的自有回执处理抛异常：{}", type.id(), broken.toString());
            }
            if (chained != null) {
                try {
                    chained.receive(payload, context);
                } catch (Throwable theirs) {
                    LOGGER.warn("[neko-client] 通道 {} 的前一个接收器抛异常：{}", type.id(), theirs.toString());
                }
            }
        });
        return chained != null;
    }
}
