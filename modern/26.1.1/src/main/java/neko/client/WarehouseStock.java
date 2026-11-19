package neko.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import neko.voidwarehouse.restock.PrinterQueryPayload;
import neko.voidwarehouse.restock.PrinterQueryResultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 虚空仓库的<b>只读库存快照</b>（物品 id → 数量）。
 *
 * <p>0.5.6 起唯一的消费者是「容器填充物合计」界面（{@code ContainerFillMaterialList}）：
 * 那一列「已有」= 背包 + 这里。它不再碰 Litematica 原生材料列表 ——
 * 那是 {@code litematica-printer} 已经实现的东西，两边都注入只会互相打架。</p>
 *
 * <h2>数据从哪来</h2>
 * <p>服务端能力由 {@code carpet-Neko-Addition} 提供：客户端发一条只读的
 * {@code carpet_neko_addition:printer_query}，服务端按物品 id 回
 * {@code printer_query_result}。这条通道与 {@code litematica-printer} 用的是同一条，
 * 所以两条路只可能有一条真的收到数据 —— 见下面的「共存」。</p>
 *
 * <h2>与 litematica-printer 共存</h2>
 * <p>Fabric 的全局接收器<b>每个通道只能有一个</b>，先注册者独占。printer 已经装了
 * 的时候<b>不去抢</b>：直接反射读它已同步的 {@code VoidWarehouseMaterialCache}，
 * 既不重复发包，也不会让 printer 的材料列表失去回执。没装 printer 时才自己注册
 * 接收器、自己发查询。<b>两条路都是只读的</b>，不会改动仓库库存。</p>
 *
 * <h2>什么时候才真正发查询</h2>
 * <p>由「有没有人在看那张合计表」驱动：界面每列一次就调 {@link #noteDemand}，
 * 只有最近 {@value #DEMAND_TTL_TICKS} tick 内被问过，才按
 * {@value #REFRESH_INTERVAL_TICKS} tick 的节奏刷新。<b>没人看就不发</b> ——
 * 这条通道是服务端资源，不该为关掉的界面持续发包。</p>
 *
 * <h2>为什么客户端不做全仓扫描</h2>
 * <p>服务端查询已经改成按请求 id 查 {@code VoidWarehouseData} 的物品索引；
 * 客户端这里同样只维护「id → 数量」快照。材料列表每帧取一行，不能在这条路径上
 * 做任何与仓库格数相关的遍历。</p>
 */
@Environment(EnvType.CLIENT)
public final class WarehouseStock {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /** 正常刷新节奏（5 秒）。 */
    private static final int REFRESH_INTERVAL_TICKS = 100;
    /** 等回执的超时；超时就放行下一轮，避免一次丢包把刷新永久卡死。 */
    private static final int RESPONSE_TIMEOUT_TICKS = 40;
    /** 服务端回「限流/规则没开」后的退避时间。 */
    private static final int REJECT_BACKOFF_TICKS = 1200;
    /** 需求有效窗口：超过这个时间没人来问，就认为材料列表已经关掉了。 */
    private static final int DEMAND_TTL_TICKS = 200;
    /** 快照上限，与服务端回执上限一致，防止被改过的服务端撑爆内存。 */
    private static final int MAX_CACHE_ENTRIES = PrinterQueryResultPayload.MAX_ENTRIES;

    /** printer 的缓存类；只在反射可用时非空。 */
    private static final String PRINTER_CACHE_CLASS =
            "me.aleksilassila.litematica.printer.integration.voidwarehouse.VoidWarehouseMaterialCache";

    private static boolean initialized;
    private static boolean printerLoaded;
    /** 自己是否真的拿到了回执通道（false 表示走 printer 缓存，或通道不可用）。 */
    private static boolean ownsChannel;
    private static Object printerCache;
    private static Method printerAmountOfMethod;
    private static Method printerIsReadyMethod;

    private static final Map<String, Long> AMOUNTS = new HashMap<>();
    private static boolean ready;
    private static boolean failureLogged;
    /** 快照版本：每次整份替换 +1，界面靠它决定要不要重算「已有」。 */
    private static long snapshotVersion;

    /**
     * 材料列表当前要看的物品 id（保持顺序便于排查），用来拼查询清单。
     *
     * <p>🔴 0.5.7 起<b>发出去之后不再清空</b>：所谓 5 秒一次刷新本来就是靠它拼清单的，
     * 清掉就只剩「打开界面那一次」真的问过。换了投影由 {@link #forgetDemand()} 整份替换。
     * 材料列表可能在构建线程调用，需同步。</p>
     */
    private static final Set<String> WATCHED = new LinkedHashSet<>();
    /**
     * 复用 printer 缓存时没有版本号可听：自己记住上次读到的数，变了才让界面重算。
     * 与 {@link #WATCHED} 共用同一把锁。
     */
    private static final Map<String, Long> LAST_SEEN = new HashMap<>();
    private static long lastFingerprintTick = Long.MIN_VALUE;
    /** 指纹别每 tick 都比一次（上百个 id × 反射调用，没必要 20 Hz）。 */
    private static final int FINGERPRINT_INTERVAL_TICKS = 10;
    private static volatile long lastDemandTick = Long.MIN_VALUE;

    private static int nextNonce = 1;
    private static int latestNonce;
    private static boolean pending;
    private static long sentAtTick;
    private static long lastRequestTick = Long.MIN_VALUE;
    private static long rejectedUntilTick = Long.MIN_VALUE;

    private WarehouseStock() {
    }

    /**
     * 注册查询通道。由客户端入口按版本传入两个 registry
     * （26.x 叫 {@code serverboundPlay/clientboundPlay}，1.21.11 叫 {@code playC2S/playS2C}）。
     */
    public static void init(PayloadTypeRegistry<?> c2s, PayloadTypeRegistry<?> s2c) {
        if (initialized) {
            return;
        }
        initialized = true;

        PayloadChannels.register(c2s, "printer_query",
                PrinterQueryPayload.TYPE, PrinterQueryPayload.CODEC);
        PayloadChannels.register(s2c, "printer_query_result",
                PrinterQueryResultPayload.TYPE, PrinterQueryResultPayload.CODEC);

        printerLoaded = FabricLoader.getInstance().isModLoaded("litematica-printer");
        if (printerLoaded) {
            // 🔴 不抢 printer 的接收器：同一条通道只能有一个全局接收器，抢了会让
            // printer 自己的材料列表永远停在 0。直接读它已经同步好的缓存。
            resolvePrinterCache();
            if (printerCache == null) {
                LOGGER.info("[虚空仓库库存] 已安装 litematica-printer，但读不到它的虚空仓库缓存；"
                        + "本功能按 0 处理，不会抢注册、也不会影响 printer。");
            }
        } else if (PayloadChannels.usable(PrinterQueryPayload.TYPE)
                && PayloadChannels.usable(PrinterQueryResultPayload.TYPE)) {
            try {
                ownsChannel = ClientPlayNetworking.registerGlobalReceiver(
                        PrinterQueryResultPayload.TYPE,
                        (payload, context) -> receive(payload));
            } catch (RuntimeException exception) {
                ownsChannel = false;
                logOnce("无法注册虚空仓库库存回执接收器", exception);
            }
            if (!ownsChannel) {
                LOGGER.info("[虚空仓库库存] {} 的接收器已被其它模组占用，本功能按 0 处理。",
                        PrinterQueryResultPayload.TYPE.id());
            }
        } else {
            LOGGER.info("[虚空仓库库存] printer_query 通道不归本模组（可能被其它模组占用），"
                    + "本功能按 0 处理。");
        }

        ClientTickEvents.END_CLIENT_TICK.register(WarehouseStock::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
    }

    /** 通道被 printer 占用时，反射拿它的缓存读取方法；拿不到就保持 null。 */
    private static void resolvePrinterCache() {
        if (!printerLoaded) {
            return;
        }
        try {
            Class<?> cacheClass = Class.forName(PRINTER_CACHE_CLASS, false,
                    WarehouseStock.class.getClassLoader());
            Object instance = cacheClass.getMethod("get").invoke(null);
            if (instance == null) {
                return;
            }
            Method amountOf = cacheClass.getMethod("amountOf", String.class);
            Method isReady = cacheClass.getMethod("isReady");
            printerCache = instance;
            printerAmountOfMethod = amountOf;
            printerIsReadyMethod = isReady;
        } catch (Throwable throwable) {
            printerCache = null;
            printerAmountOfMethod = null;
            printerIsReadyMethod = null;
            logOnce("无法读取 litematica-printer 的虚空仓库缓存", throwable);
        }
    }

    /**
     * 批量登记「这些物品的库存有人在看」。
     *
     * <p>容器填充物合计界面一次要几十上百种，逐个造 {@link ItemStack} 只为取 id 太浪费；
     * 服务端认的就是 id 字符串，这里直接进清单。</p>
     */
    public static void noteDemand(Collection<String> ids) {
        // 🔴 不再因为「通道归 printer」就跳过：通道不在我们手上时，这份清单用来
        //    比对 printer 缓存的数字有没有变（fingerprintPrinterCache），照样必须有。
        if (!isSourceEnabled() || ids == null || ids.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        if (client.level == null) {
            return;
        }
        lastDemandTick = client.level.getGameTime();
        synchronized (WATCHED) {
            for (String id : ids) {
                if (id == null || id.isEmpty()) {
                    continue;
                }
                if (WATCHED.size() >= PrinterQueryPayload.MAX_IDS) {
                    break;
                }
                WATCHED.add(id);
            }
        }
    }

    /**
     * 换了一张材料列表：旧需求与「上次看到的数」一起丢掉。
     *
     * <p>不清就会跨投影累积 —— 上限一撞，新投影的物品永远问不到。</p>
     */
    public static void forgetDemand() {
        synchronized (WATCHED) {
            WATCHED.clear();
            LAST_SEEN.clear();
        }
        lastFingerprintTick = Long.MIN_VALUE;
    }

    /**
     * 记录「有人在问这个物品的库存」。由材料行的已有数量计算调用，同时充当查询驱动信号。
     */
    public static void noteDemand(ItemStack stack) {
        if (!isSourceEnabled() || stack == null || stack.isEmpty()) {
            return;
        }
        String id = idOf(stack);
        if (id != null) {
            noteDemand(List.of(id));
        }
    }

    /** 这批目标物品在虚空仓库里的合计数量；无数据时 0。 */
    public static long amountFor(ItemStack stack) {
        if (!isSourceEnabled() || stack == null || stack.isEmpty()) {
            return 0L;
        }
        String id = idOf(stack);
        return id == null ? 0L : amountOf(id);
    }

    /** 快照版本号；变了就说明「已有」该重算。 */
    public static long snapshotVersion() {
        return snapshotVersion;
    }

    /** 这种物品在虚空仓库里的合计数量（含全部数据组件变体）；还没拿到快照时 0。 */
    public static long amountOf(String id) {
        if (ownsChannel) {
            synchronized (AMOUNTS) {
                if (!ready) {
                    return 0L;
                }
                Long value = AMOUNTS.get(id);
                return value == null ? 0L : value;
            }
        }
        return printerWarehouseAmountOf(id);
    }

    /**
     * 材料列表的行是不是由 litematica-printer 在画。
     *
     * <p>它给 {@code WidgetMaterialListEntry.render} 加了 Redirect：缓存就绪时
     * 「缺失」列画的是 {@code netGap(total, getCountMissing())} 再减 {@code getCountAvailable()}
     * —— 也就是它假定 {@code missing} 与 {@code available} 是<b>两个独立的量</b>
     * （原版语义：missing = 还没建成的方块数，available = 手上已有的货）。
     * 本表的 missing 本来就是「需求 − 已有」，让它再减一次会把缺口算成
     * {@code total - 2 × available}。所以这个值为 true 时
     * {@code ContainerEntry#getCountMissing()} 必须给 {@code total}，让它的减法落在正确的起点上。</p>
     */
    public static boolean rowsRenderedByPrinter() {
        return printerCache != null && printerReady();
    }

    /** printer 缓存是否已经同步到数据（反射调用；出错按「没有」处理）。 */
    private static boolean printerReady() {
        if (printerCache == null || printerIsReadyMethod == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(printerIsReadyMethod.invoke(printerCache));
        } catch (Throwable throwable) {
            printerCache = null;
            printerAmountOfMethod = null;
            printerIsReadyMethod = null;
            return false;
        }
    }

    /** 读 printer 的缓存；不可用或没同步到任何数据时返回 0。 */
    private static long printerWarehouseAmountOf(String id) {
        if (printerCache == null || printerAmountOfMethod == null || printerIsReadyMethod == null) {
            return 0L;
        }
        try {
            if (!Boolean.TRUE.equals(printerIsReadyMethod.invoke(printerCache))) {
                return 0L;
            }
            Object value = printerAmountOfMethod.invoke(printerCache, id);
            return value instanceof Long amount ? amount : 0L;
        } catch (Throwable throwable) {
            printerCache = null;
            printerAmountOfMethod = null;
            printerIsReadyMethod = null;
            logOnce("读取 litematica-printer 虚空仓库缓存失败，本次会话不再尝试", throwable);
            return 0L;
        }
    }

    /**
     * 盯 printer 缓存里的数字有没有变。
     *
     * <p>它的缓存只有 {@code apply / clear / amountOf / isReady / size}，没有任何变更通知，
     * 所以只能自己比对：每 {@value #FINGERPRINT_INTERVAL_TICKS} tick 把
     * {@link #WATCHED} 里的 id 读一遍，有变化就 {@code snapshotVersion++}，
     * 界面据此重算「已有 / 缺失」和表头合计。</p>
     */
    private static void fingerprintPrinterCache(long now) {
        if (lastFingerprintTick != Long.MIN_VALUE
                && now - lastFingerprintTick < FINGERPRINT_INTERVAL_TICKS) {
            return;
        }
        lastFingerprintTick = now;
        if (printerCache == null) {
            return;
        }
        boolean changed = false;
        synchronized (WATCHED) {
            for (String id : WATCHED) {
                long amount = printerWarehouseAmountOf(id);
                Long previous = LAST_SEEN.put(id, amount);
                if (previous == null || previous.longValue() != amount) {
                    changed = true;
                }
            }
        }
        if (changed) {
            snapshotVersion++;
        }
    }

    private static void tick(Minecraft client) {
        if (client.player == null || client.level == null) {
            clear();
            return;
        }
        if (!isSourceEnabled() || !isSupported()) {
            if (ready || !AMOUNTS.isEmpty()) {
                clearSnapshot();
            }
            return;
        }

        long now = client.level.getGameTime();

        if (!ownsChannel) {
            // 复用 printer 的缓存：这条通道的接收器在它手上，我们既发不出去也收不到回执，
            // 唯一能做的是盯着它缓存的数字有没有变（变了才让界面重算）。
            fingerprintPrinterCache(now);
            return;
        }

        if (pending) {
            if (now - sentAtTick <= RESPONSE_TIMEOUT_TICKS) {
                return;
            }
            // 超时放行，否则一次丢包就把刷新永久卡死。
            pending = false;
        }
        if (now < rejectedUntilTick) {
            return;
        }
        if (lastRequestTick != Long.MIN_VALUE && now - lastRequestTick < REFRESH_INTERVAL_TICKS) {
            return;
        }
        if (lastDemandTick == Long.MIN_VALUE || now - lastDemandTick > DEMAND_TTL_TICKS) {
            // 没人在看材料列表：不再发包，但保留已有快照，避免界面刚关就闪回 0。
            return;
        }

        List<String> ids;
        synchronized (WATCHED) {
            if (WATCHED.isEmpty()) {
                return;
            }
            ids = new ArrayList<>(WATCHED);
        }

        lastRequestTick = now;
        int nonce = send(ids);
        if (nonce == 0) {
            pending = false;
            return;
        }
        pending = true;
        sentAtTick = now;
    }

    private static int send(List<String> ids) {
        int nonce = nextNonce++;
        if (nextNonce <= 0) {
            nextNonce = 1;
        }
        latestNonce = nonce;
        try {
            ClientPlayNetworking.send(new PrinterQueryPayload(nonce, ids));
            return nonce;
        } catch (RuntimeException exception) {
            logOnce("发送虚空仓库库存查询失败", exception);
            return 0;
        }
    }

    private static void receive(PrinterQueryResultPayload payload) {
        if (payload.nonce() != latestNonce) {
            // 旧请求的迟到回执：丢掉，别让它覆盖更新的快照。
            pending = false;
            return;
        }
        switch (payload.status()) {
            case PrinterQueryResultPayload.OK -> {
                apply(payload.itemIds(), payload.amounts());
                pending = false;
            }
            case PrinterQueryResultPayload.THROTTLED -> {
                // 服务端限流：退避一段时间再试。
                rejectedUntilTick = lastRequestTick + REJECT_BACKOFF_TICKS;
                pending = false;
            }
            case PrinterQueryResultPayload.RULE_OFF -> {
                // 规则没开：清空快照，避免继续显示上一台服务器的库存。规则可能被临时打开，
                // 所以只退避、不永久禁用。
                rejectedUntilTick = lastRequestTick + REJECT_BACKOFF_TICKS;
                clearSnapshot();
                pending = false;
            }
            default -> pending = false;
        }
    }

    /** 用一份新快照<b>整体替换</b>旧内容：换了投影之后旧键不该继续挂着。 */
    private static void apply(List<String> ids, List<Long> values) {
        if (ids == null || values == null) {
            return;
        }
        int size = Math.min(Math.min(ids.size(), values.size()), MAX_CACHE_ENTRIES);
        synchronized (AMOUNTS) {
            AMOUNTS.clear();
            for (int index = 0; index < size; index++) {
                String id = ids.get(index);
                Long value = values.get(index);
                if (id == null || id.isEmpty() || value == null || value <= 0L) {
                    continue;
                }
                AMOUNTS.merge(id, value, Long::sum);
            }
            ready = true;
        }
        snapshotVersion++;
    }

    /** 服务端能力探测：只有服务端装了对应模组并声明了这条通道才发得出去。 */
    public static boolean isSupported() {
        if (!initialized) {
            return false;
        }
        if (!ownsChannel) {
            return printerCache != null;
        }
        try {
            return ClientPlayNetworking.canSend(PrinterQueryPayload.TYPE)
                    && PayloadChannels.usable(PrinterQueryPayload.TYPE);
        } catch (RuntimeException noConnection) {
            return false;
        }
    }

    /**
     * 本功能是否可能给出任何数字。
     *
     * <p>0.5.6 起没有独立开关：入口是「容器填充物合计」界面，打开才登记需求。
     * 通道不可用时仍返回 true —— 数字会一直是 0，但界面本身照常能看需求。</p>
     */
    public static boolean isSourceEnabled() {
        return initialized;
    }

    /** 给进服日志用：一眼看出这次是自有通道、复用 printer，还是不可用。 */
    public static String status() {
        if (!isSourceEnabled()) {
            return "未初始化";
        }
        if (ownsChannel) {
            return "开（自有查询通道）";
        }
        if (printerCache != null) {
            return "开（复用 litematica-printer 缓存）";
        }
        return "开（通道不可用，按 0 处理）";
    }

    private static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() == Items.AIR) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        // 服务端会拿这个字符串再解析一次，所以必须是 namespace:path 的完整形式。
        return key == null ? null : key.toString();
    }

    private static void clearSnapshot() {
        synchronized (AMOUNTS) {
            AMOUNTS.clear();
            ready = false;
        }
        snapshotVersion++;
    }

    private static void clear() {
        clearSnapshot();
        forgetDemand();
        lastDemandTick = Long.MIN_VALUE;
        lastRequestTick = Long.MIN_VALUE;
        rejectedUntilTick = Long.MIN_VALUE;
        pending = false;
        latestNonce = 0;
    }

    private static void logOnce(String message, Throwable throwable) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        LOGGER.warn("[虚空仓库库存] {}", message, throwable);
    }
}
