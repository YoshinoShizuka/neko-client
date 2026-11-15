package neko.client;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 通道归属自检：<b>「注册没抛异常」不等于「这个通道归我们」</b>。
 *
 * <h2>为什么需要这一层</h2>
 * <p>Fabric 的 payload 注册表是 <b>按通道 id 严格去重</b>的，而「谁能编码这个 id」
 * 取决于<b>先注册那一方塞进去的 {@code StreamCodec}</b>。于是有三种局面，
 * 前两种无害、第三种会把客户端踢下线：</p>
 * <ol>
 *   <li><b>专用服务器</b>：两边是两个 JVM，客户端这边表是空的，我们自己注册成功
 *       ⇒ 编码用的一定是我们的 codec，没问题；</li>
 *   <li><b>单人存档</b>：服务端模组（carpet）的 {@code main} 入口先注册了<b>同一个类</b>
 *       （同 FQCN 在 Fabric knot 里只加载一份），这里的注册抛
 *       {@link IllegalArgumentException} —— 表里那份 codec 就是我们自己那个静态字段，
 *       引用相等，也没问题；</li>
 *   <li>🔴 <b>撞名的是另一个类</b>（别的模组占了同一个通道 id，或者服务端模组换了包名/版本）：
 *       注册同样抛 {@link IllegalArgumentException}，但表里那份 codec 属于<b>别人的类</b>。
 *       我们一 {@code send}，它就会在编码阶段 cast 失败抛
 *       {@code Can't encode 'X', expected 'X'}（两个类同名不同包，报错看着一模一样）——
 *       这异常发生在 netty 线程上，<b>catch 不到，只会表现为断线</b>。</li>
 * </ol>
 *
 * <p>所以注册完之后必须<b>回头核对归属</b>：把这个 id 名下登记的那份 codec 取出来，
 * 跟我们的 {@code CODEC} 静态字段比<b>引用</b>（不是 {@code equals}）。核对不上就
 * 把该通道标记为不可用，发送侧一律先问 {@link #usable} —— 宁可搜索框不出现，
 * 也不许因为一次编码失败把玩家踢下线。</p>
 *
 * <h2>为什么敢读 impl 的 get(Type)</h2>
 * <p>{@code PayloadTypeRegistry} 这个公开接口只暴露 register 系列，<b>没有读接口</b>；
 * 唯一能问「这个 id 现在归谁」的是实现类上那个 {@code public TypeAndCodec get(Type)}。
 * 这里用反射调用、只读、全程 {@code catch Throwable}，且<b>读不到就放行</b>
 * （见 {@link #ownsChannel}）—— 也就是说 Fabric 哪天改了实现，最坏结果是退化成
 * 0.4.1 之前的行为，不会凭空关掉功能。</p>
 */
final class PayloadChannels {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /** 通道 id（{@code namespace:path}）→ 能不能用 */
    private static final Map<String, Boolean> USABLE = new LinkedHashMap<>();
    /** 通道 id → 一句人看的体检结论，启动日志里一次性打出来 */
    private static final Map<String, String> REPORT = new LinkedHashMap<>();

    private static Method registryGet;
    private static boolean registryGetResolved;

    private PayloadChannels() {
    }

    /**
     * 注册一条通道，并当场核对归属。
     *
     * @param registry 目标注册表（{@code serverboundPlay()} / {@code clientboundPlay()}，
     *                 1.21.11 那套叫 {@code playC2S()} / {@code playS2C()}）
     * @param label    日志里用的短名，如 {@code fill_request}
     */
    static <T extends CustomPacketPayload> void register(PayloadTypeRegistry<?> registry, String label,
                                                         CustomPacketPayload.Type<T> type, StreamCodec<?, T> codec) {
        String channel = type.id().toString();
        boolean conflict = false;

        try {
            registerRaw(registry, type, codec);
        } catch (IllegalArgumentException alreadyRegistered) {
            // 单人存档的常态（服务端模组先注册），也可能真是别人占了 id —— 下面靠归属核对区分
            conflict = true;
        } catch (Throwable unexpected) {
            USABLE.put(channel, Boolean.FALSE);
            REPORT.put(channel, label + " 注册失败");
            LOGGER.error("[neko-client] 通道 {}（{}）注册抛了非预期异常，该功能已关闭：{}",
                    label, channel, unexpected.toString());
            return;
        }

        Boolean ours = ownsChannel(registry, type, codec);

        if (ours == null) {
            // 读不到注册表：不改变任何行为，只留一条可查的痕迹
            USABLE.put(channel, Boolean.TRUE);
            REPORT.put(channel, label + " 已注册（归属未核对）");
            if (conflict) {
                LOGGER.warn("[neko-client] 通道 {}（{}）已被先注册，且读不到注册表，归属核对被跳过。"
                        + "单人存档里这属于正常：服务端模组先注册了同一个类，两边共用一份 codec。",
                        label, channel);
            }
            return;
        }

        if (!ours) {
            USABLE.put(channel, Boolean.FALSE);
            REPORT.put(channel, label + " 被别的 codec 占用");
            LOGGER.error("[neko-client] 通道 {}（{}）名下登记的不是本模组的 codec —— "
                            + "多半是另一个模组用了同一个通道 id，或服务端模组与客户端不配套。"
                            + "该功能已自行关闭：宁可功能不可用，也不能让编码失败把客户端踢下线。",
                    label, channel);
            return;
        }

        USABLE.put(channel, Boolean.TRUE);
        REPORT.put(channel, label + " 归本模组");
        if (conflict) {
            LOGGER.warn("[neko-client] 通道 {}（{}）已被先注册，归属核对通过 —— 单人存档里这是正常现象："
                    + "服务端模组先注册了同一个类（同 FQCN 只加载一份），codec 也就是同一个对象。",
                    label, channel);
        }
    }

    /** 发送前的门禁：{@code false} ⇒ 这条通道一发就会炸，功能侧必须自己退让 */
    static boolean usable(CustomPacketPayload.Type<?> type) {
        Boolean ok = USABLE.get(type.id().toString());
        // 没登记过（理论上不会发生）就当能用，避免自检本身变成故障源
        return ok == null || ok;
    }

    /** 四条通道是不是全部归本模组 */
    static boolean allUsable() {
        for (Boolean ok : USABLE.values()) {
            if (!ok) {
                return false;
            }
        }
        return true;
    }

    /** 体检结论，一行一条，给启动日志 */
    static String summary() {
        return String.join("；", REPORT.values());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void registerRaw(PayloadTypeRegistry registry, CustomPacketPayload.Type type, StreamCodec codec) {
        registry.register(type, codec);
    }

    /**
     * 这条通道名下登记的是不是<b>我们这一份</b> codec。
     *
     * @return {@code TRUE} 是；{@code FALSE} 被别的类占了（或压根不在表里）；
     *         {@code null} 读不到（API 变了 / 反射被拦），调用方按「放行」处理
     */
    private static Boolean ownsChannel(PayloadTypeRegistry<?> registry, CustomPacketPayload.Type<?> type,
                                       StreamCodec<?, ?> codec) {
        Method get = registryGet();
        if (get == null) {
            return null;
        }
        try {
            Object typeAndCodec = get.invoke(registry, type);
            if (typeAndCodec == null) {
                return Boolean.FALSE;
            }
            // 🔴 不按名字取 accessor：混淆版本的 record 访问器可能被改成 comp_xxx，
            // 「零参 + 返回 StreamCodec」才是唯一稳定的判据（TypeAndCodec 里只有这一个）。
            for (Method accessor : typeAndCodec.getClass().getMethods()) {
                if (accessor.getParameterCount() == 0
                        && StreamCodec.class.isAssignableFrom(accessor.getReturnType())) {
                    // 必须比引用：不同 class 里的两个同名类，codec 是两个不同的对象
                    return accessor.invoke(typeAndCodec) == codec;
                }
            }
            return null;
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static synchronized Method registryGet() {
        if (!registryGetResolved) {
            registryGetResolved = true;
            try {
                Class<?> impl = Class.forName("net.fabricmc.fabric.impl.networking.PayloadTypeRegistryImpl");
                try {
                    registryGet = impl.getMethod("get", CustomPacketPayload.Type.class);
                } catch (NoSuchMethodException renamed) {
                    // 退路：按形状找 —— 单参、名字叫 get、返回值是 TypeAndCodec
                    for (Method m : impl.getMethods()) {
                        if (m.getParameterCount() == 1 && m.getName().equals("get")
                                && m.getParameterTypes()[0].isAssignableFrom(CustomPacketPayload.Type.class)) {
                            registryGet = m;
                            break;
                        }
                    }
                }
            } catch (Throwable notFound) {
                registryGet = null;
            }
            if (registryGet == null) {
                LOGGER.debug("[neko-client] 读不到 PayloadTypeRegistryImpl#get，通道归属核对整体跳过（退化为 0.4.0 的行为）");
            }
        }
        return registryGet;
    }
}
