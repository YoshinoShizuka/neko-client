package neko.client;

import fi.dy.masa.malilib.util.InfoUtils;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import neko.client.fill.QuickClientScreenAccess;
import neko.voidwarehouse.open.OpenWarehousePayload;
import neko.voidwarehouse.open.OpenWarehouseResultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 「按键 → 服务端开虚空仓库界面」的客户端收发管家。
 *
 * <h2>一次按键 = 一个包，绝不在 tick 里轮询</h2>
 * <p>服务端对这条通道有 250 ms 分表限流（见 {@code OpenWarehouseNetwork#MIN_INTERVAL_MS}），
 * 那是防客户端写错的兜底，不是给客户端用的节奏。这里的热键走 malilib 的
 * {@code KeyAction.PRESS} 回调 —— 本身就是按下边沿，再套一层 tick 轮询只会把限流打爆。</p>
 *
 * <h2>回执只用来「解释失败」</h2>
 * <p>成功时客户端会收到原版 {@code ClientboundOpenScreenPacket}，不需要自己开界面，
 * 更不需要缓存菜单内容（数据全在服务端）。回执的唯一职责是让「规则没开 / 仓库读不出来」
 * 这类<b>静默失败</b>有一句人话；所以判据要严格：只认最后一次发出的 nonce，迟到的包
 * 一律丢掉，否则上一局那句「未启用」会冒到这一局。</p>
 *
 * <h2>接收器为什么走 {@link PayloadReceivers#attach}</h2>
 * <p>这条 S2C 目前只有本模组注册，但 {@code registerGlobalReceiver} 的规则是先注册者赢、
 * 后注册者拿 {@code false} 还不报错。用 attach 统一兜住「别人先占」的情形：取下来、挂上
 * 「我们的 + 他们的」链式版，谁先初始化都不会哑。这与取货 / 退料回执是同一条铁律。</p>
 */
public final class WarehouseOpenNetwork {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    private static volatile boolean initialized = false;
    /** 最后一次发出的 nonce；-1 = 还没发过 / 已断线 */
    private static volatile int lastSentNonce = -1;
    private static int nextNonce = 1;
    private static volatile long lastRuleOffNoticeAt = 0L;

    private WarehouseOpenNetwork() {
    }

    /** 客户端初始化时调一次；registry 由入口按版本传入（26.x / 1.21.11 方法名不同） */
    public static void init(PayloadTypeRegistry<?> c2s, PayloadTypeRegistry<?> s2c) {
        if (initialized) {
            return;
        }
        // C2S 类型不在这里 register 也行（单人同进程时服务端已注册），但必须走
        // PayloadChannels 的幂等注册 + 归属核对：专用服务器上客户端这张表是空的，
        // 不注册就会退化成 DiscardedPayload —— 不崩、但整包丢。
        PayloadChannels.register(c2s, "open_warehouse",
                OpenWarehousePayload.TYPE, OpenWarehousePayload.CODEC);
        PayloadChannels.register(s2c, "open_warehouse_result",
                OpenWarehouseResultPayload.TYPE, OpenWarehouseResultPayload.CODEC);

        if (PayloadReceivers.attach(OpenWarehouseResultPayload.TYPE, WarehouseOpenNetwork::receive)) {
            LOGGER.info("[虚空仓库开门] 回执接收器原先被占用（已接管并转发，两边都收得到）");
        }

        // nonce 不持久化：断线重连后从 1 开始，与新连接上的回执重新配对
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> reset());
        initialized = true;
    }

    private static void reset() {
        lastSentNonce = -1;
        nextNonce = 1;
        lastRuleOffNoticeAt = 0L;
    }

    /** 对端认不认这条通道（旧服务端 / 纯原版服 ⇒ false） */
    public static boolean isSupported() {
        if (!initialized) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(OpenWarehousePayload.TYPE)
                    && PayloadChannels.usable(OpenWarehousePayload.TYPE);
        } catch (RuntimeException noConnection) {
            return false;
        }
    }

    /**
     * 发一次开界面请求。
     *
     * @return {@code true} = 包已发出（界面由服务端开，结果看回执 / 开屏包）
     */
    public static boolean requestOpen(Minecraft client) {
        if (client == null || client.player == null) {
            return false;
        }
        // 🔴 不直接读 Minecraft#screen：26.2+ 它搬进了 Gui.screen()，26.1.x / 1.21.11
        // 才是公开字段。项目里那份跨版本适配器已经把五代差异收在一处，这里只管问「有没有界面」。
        if (!WarehouseOpenModel.canRequest(QuickClientScreenAccess.currentScreen(client) != null)) {
            // 界面开着的按键属于界面输入；也顺带让搜索框的快捷键不被这条截走
            return false;
        }
        if (!isSupported()) {
            InfoUtils.printActionbarMessage("message.neko_client.open_warehouse_unsupported");
            return false;
        }

        int nonce = nextNonce++;
        lastSentNonce = nonce;
        SearchConfig.audit("[虚空仓库开门] 请求已发出 nonce={}", nonce);
        try {
            ClientPlayNetworking.send(new OpenWarehousePayload(nonce));
        } catch (RuntimeException failed) {
            lastSentNonce = -1;
            LOGGER.warn("[虚空仓库开门] 发包失败：{}", failed.toString());
            InfoUtils.printActionbarMessage("message.neko_client.open_warehouse_failed");
            return false;
        }
        return true;
    }

    private static void receive(OpenWarehouseResultPayload payload, ClientPlayNetworking.Context context) {
        context.client().execute(() -> onResult(payload.nonce(), payload.status()));
    }

    /** 回执处理（包分配在客户端主线程执行）；判据全在 {@link WarehouseOpenModel} */
    static void onResult(int nonce, int status) {
        if (WarehouseOpenModel.isLate(nonce, lastSentNonce)) {
            SearchConfig.trace("[虚空仓库开门] 忽略迟到回执 nonce={} status={}（最后发出 {}）",
                    nonce, status, lastSentNonce);
            return;
        }
        if (status == OpenWarehouseResultPayload.OK
                || status == OpenWarehouseResultPayload.ALREADY_OPEN) {
            return;
        }
        if (status == OpenWarehouseResultPayload.BAD_REQUEST) {
            LOGGER.warn("[虚空仓库开门] 服务端回 BAD_REQUEST nonce={}", nonce);
            return;
        }
        String key = WarehouseOpenModel.messageKeyFor(status);
        if (key == null) {
            // THROTTLED / NONE / 未知状态：静默，且绝不立刻重发
            SearchConfig.trace("[虚空仓库开门] 静默状态 nonce={} status={}", nonce, status);
            return;
        }
        long now = System.currentTimeMillis();
        if (status == OpenWarehouseResultPayload.RULE_OFF) {
            if (WarehouseOpenModel.shouldThrottleRuleOff(now, lastRuleOffNoticeAt)) {
                return;
            }
            lastRuleOffNoticeAt = now;
        }
        InfoUtils.printActionbarMessage(key);
        SearchConfig.audit("[虚空仓库开门] 提示 nonce={} status={} key={}", nonce, status, key);
    }
}
