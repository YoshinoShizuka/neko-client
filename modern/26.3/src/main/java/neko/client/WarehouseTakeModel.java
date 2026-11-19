package neko.client;

/**
 * 「热键从虚空仓库补货」的<b>纯逻辑判据</b>：不引用任何 Minecraft / malilib 类型。
 *
 * <h2>为什么单独拆一层</h2>
 * <p>这条链路的坑全在「怎么算成功」和「哪些失败要说话」上，而不是在发包上：
 * 网络层写错一个 nonce 比较、或者把 {@code OK} 当成「一定到货」，真机上的表现都是
 * 「提示说取到了、背包里却没多东西」——而且没有任何异常。0.4.x 的取满手势已经在
 * 「功能可用 ≠ 语义正确」上栽过一次，这里把判据抽成纯类让 JUnit 直接咬。</p>
 *
 * <h2>成功判据为什么要取交集</h2>
 * <p>服务端 {@code PrinterTakeResultPayload.OK} 只代表「已经往背包塞了」；客户端还
 * 必须确认<b>背包里这个物品的件数真的变多了</b>。单人同进程时服务端的背包就是
 * 客户端的背包，这一步看着多余；专用服务器上是一次真实的跨进程同步，确认不可省。</p>
 */
public final class WarehouseTakeModel {

    /** 请求后等多久（毫秒）仍没回执就判超时：50ms 限流 + 网络往返，2 秒足够 */
    public static final long REPLY_TIMEOUT_MS = 2000L;

    private WarehouseTakeModel() {
    }

    /**
     * 回执是否算「真的取到货了」。
     *
     * @param status     回执状态码（见 {@code PrinterTakeResultPayload} 常量）
     * @param heldBefore 发包前背包里该物品的件数
     * @param heldAfter  回执到达后背包里该物品的件数
     */
    public static boolean delivered(int status, int heldBefore, int heldAfter) {
        return status == STATUS_OK && heldAfter > heldBefore;
    }

    /**
     * 撞限流时要不要退避重试。
     *
     * <p>只重试 THROTTLED：其它失败态重试没有意义（没货就是没货、规则没开不会自己开），
     * 盲目重试反而会把 50ms 限流一直撞满。</p>
     */
    public static boolean shouldRetry(int status) {
        return status == STATUS_THROTTLED;
    }

    /**
     * 动作栏提示的翻译键。返回 {@code null} = 不提示（成功交给服务端的那条消息）。
     *
     * <p>成功时不重复提示是有意的：服务端已经发了一条带数量的
     * {@code [虚空仓库] 取走 N 个 …}，客户端再闪一条只会刷屏。失败时服务端那条不发，
     * 客户端必须说清楚为什么没拿到，否则表现就是「按了没反应」。</p>
     */
    public static String hintKey(int status) {
        switch (status) {
            case STATUS_RULE_OFF:
                return "message.neko_client.warehouse_take_rule_off";
            case STATUS_EMPTY:
                return "message.neko_client.warehouse_take_empty";
            case STATUS_INVENTORY_FULL:
                return "message.neko_client.warehouse_take_full";
            case STATUS_THROTTLED:
                return "message.neko_client.warehouse_take_throttled";
            case STATUS_BAD_REQUEST:
                return "message.neko_client.warehouse_take_bad_request";
            case STATUS_NO_REPLY:
                return "message.neko_client.warehouse_take_no_reply";
            default:
                return null;
        }
    }

    /**
     * 分配下一个流水号：正数递增，绕回时跳过 0（0 是「没发出去 / 没有请求」的哨兵）。
     */
    public static int nextNonce(int current) {
        if (current >= Integer.MAX_VALUE) {
            return 1;
        }
        return current + 1;
    }

    /** 回执状态常量（与服务端 {@code PrinterTakeResultPayload} 逐一对齐） */
    public static final int STATUS_OK = 0;
    public static final int STATUS_RULE_OFF = 1;
    public static final int STATUS_EMPTY = 2;
    public static final int STATUS_INVENTORY_FULL = 3;
    public static final int STATUS_THROTTLED = 4;
    public static final int STATUS_BAD_REQUEST = 5;
    /** 本地状态：还没收到回执（服务端的 NONE = -1，刻意不复用） */
    public static final int STATUS_NO_REPLY = -2;
}
