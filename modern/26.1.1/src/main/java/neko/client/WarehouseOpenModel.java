package neko.client;

/**
 * 「按键打开虚空仓库」的纯判据层：<b>不 import Minecraft / malilib / Fabric</b>，单测直接跑。
 *
 * <h2>为什么单独拆一层</h2>
 * <p>这条链路最容易写错的两个地方都不是「怎么发包」，而是「收到回执该不该理」和
 * 「该不该提示」。前者写错会把迟到的回执当成这一次的结果（玩家连按两下就看到莫名其妙的
 * 「仓库未启用」）；后者写错会在服务端规则关着时每按一次刷一行。两件事都能纯函数化，
 * 于是都能在 JUnit 里被钉死，不必等真机。</p>
 *
 * <h2>状态码来源</h2>
 * <p>与服务端 {@code neko.voidwarehouse.open.OpenWarehouseResultPayload} 的常量同源：
 * 0=OK、1=RULE_OFF、2=THROTTLED、3=ALREADY_OPEN、4=UNAVAILABLE、5=BAD_REQUEST。
 * 这里刻意用<b>数字</b>而不是引用那个类 —— 否则本类会依赖 MC 的 payload 类型，就测不了了。</p>
 */
public final class WarehouseOpenModel {

    /** 状态：还没收到回执（与服务端 payload 的 NONE 同值） */
    public static final int STATUS_NONE = -1;
    public static final int STATUS_OK = 0;
    public static final int STATUS_RULE_OFF = 1;
    public static final int STATUS_THROTTLED = 2;
    public static final int STATUS_ALREADY_OPEN = 3;
    public static final int STATUS_UNAVAILABLE = 4;
    public static final int STATUS_BAD_REQUEST = 5;

    /** 规则未启用时同一句提示的最小间隔；按键本身是边沿触发，3 秒足够不吵。 */
    public static final long RULE_OFF_NOTICE_COOLDOWN_MS = 3000L;

    private WarehouseOpenModel() {
    }

    /**
     * 迟到回执判据：<b>只认最后一次发出的 nonce</b>。
     *
     * <p>{@code lastSentNonce < 0} 表示还没发过（或已断线重置），这时任何回执都不能采信 ——
     * 旧连接上迟到的包会在新会话里凭空冒出一句提示。</p>
     */
    public static boolean isLate(int replyNonce, int lastSentNonce) {
        return lastSentNonce < 0 || replyNonce != lastSentNonce;
    }

    /**
     * 状态 → 动作栏提示的翻译键；{@code null} = 静默。
     *
     * <ul>
     *   <li>{@code OK}：原版 {@code ClientboundOpenScreenPacket} 随后会开界面，不用多说；</li>
     *   <li>{@code ALREADY_OPEN}：服务端已幂等处理，连点不该把页码 / 盒模式重置；</li>
     *   <li>{@code THROTTLED}：服务端分表限流，客户端发得太快，静默、不重发；</li>
     *   <li>{@code BAD_REQUEST}：不该发生，交给网络层记日志，不打扰玩家。</li>
     * </ul>
     */
    public static String messageKeyFor(int status) {
        return switch (status) {
            case STATUS_RULE_OFF -> "message.neko_client.open_warehouse_rule_off";
            case STATUS_UNAVAILABLE -> "message.neko_client.open_warehouse_unavailable";
            default -> null;
        };
    }

    /**
     * 规则未启用提示的节流：<b>被挡下的那一次不更新时间戳</b>。
     *
     * <p>若在这里顺手把时间戳推后，玩家按住不放（或连点）就会形成「每 3 秒才有一次机会
     * 重新计时」的节奏，反而一直在提示 —— 和服务端限流的同一条教训。</p>
     *
     * @return {@code true} = 这次要闭嘴
     */
    public static boolean shouldThrottleRuleOff(long now, long lastNoticeAt) {
        return lastNoticeAt > 0L && now - lastNoticeAt < RULE_OFF_NOTICE_COOLDOWN_MS;
    }

    /** 只在非界面状态响应：界面开着的按键属于界面输入，不属于「打开仓库」。 */
    public static boolean canRequest(boolean screenOpen) {
        return !screenOpen;
    }
}
