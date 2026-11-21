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
        return deliveredCount(status, heldBefore, heldAfter) > 0;
    }

    /**
     * 这次到底<b>到手多少件</b>（0 = 一件没进背包）。
     *
     * <p>容器填充要拿它跟「投影容器里要求的数量」比 —— 服务端一次只给仓库能给的全部，
     * 背包放不下就少给，所以「到手件数 &lt; 需求件数」正是「仓库（或背包）不够」的证据。</p>
     *
     * <p>只在 {@code OK} 时才算数：其它状态下服务端保证没往背包里放东西
     * （{@code INVENTORY_FULL} 连仓库都不扣），此时哪怕背包数字变了也是别的原因
     * （玩家自己捡到、另一个模组塞的），不能记成「仓库给的」。</p>
     */
    public static int deliveredCount(int status, int heldBefore, int heldAfter) {
        if (status != STATUS_OK) {
            return 0;
        }
        return Math.max(0, heldAfter - heldBefore);
    }

    /**
     * <b>没有回执</b>时的兜底判据：背包确实变多了就算到货。
     *
     * <p>为什么需要：回执可能根本到不了客户端 —— Fabric 的全局接收器是<b>先注册者赢</b>，
     * 而 {@code printer_take_result} 这条通道 {@code litematica-printer} 抢在前面注册
     * （mod id 字典序决定初始化顺序）。服务端明明把货塞进了背包，客户端却只能等到超时，
     * 于是「一件没到货」⇒ 补料链放弃续填。真机表现就是「第一次右键只取货、容器一点没填」。</p>
     *
     * <p>口径与 {@link #deliveredCount} 的区别只有一条：<b>不看状态码</b>（没有状态码可看）。
     * 归因风险存在（这 2 秒里玩家可能自己捡到同种物品），但比「功能整个失效」好得多，
     * 而且取料这一侧多算一件最多是多要一次货，不会凭空产生物品。</p>
     *
     * @param heldBefore 发包前背包里该物品的件数
     * @param heldAfter  超时那一刻背包里该物品的件数
     */
    public static int deliveredCountWithoutReply(int heldBefore, int heldAfter) {
        return Math.max(0, heldAfter - heldBefore);
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
