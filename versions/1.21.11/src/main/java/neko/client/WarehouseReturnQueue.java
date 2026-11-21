package neko.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.ShulkerBoxBlock;
import neko.client.fill.WarehouseFillPlan;
import neko.client.fill.WarehouseFillSupply;
import neko.voidwarehouse.restock.PrinterReturnPayload;
import neko.voidwarehouse.restock.PrinterReturnResultPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投影容器填充<b>用剩的仓库材料退回虚空仓库</b>的记账 + 收发管家。
 *
 * <h2>它解决的具体问题</h2>
 * <p>投影填充缺料时会走 {@code printer_take} 向仓库要货，而服务端是
 * <b>「仓库里这种物品能给的都给」</b>（{@code withdraw(sample, Integer.MAX_VALUE)}）——
 * 投影要 20 个、仓库给 64 个，填完之后多出来的 44 个就留在背包里。
 * 一个材料种类多的投影填十几个容器，背包很快被一堆「只用掉几个、剩下五六十」的整组材料占满。</p>
 *
 * <p>这条链给那些余料补上回程：填充结束（补料链停下 + 手上没有容器界面）后，
 * 按 {@code printer_return} 通道把「整格都是仓库余料」的槽位交还给仓库。</p>
 *
 * <h2>为什么是「填充结束之后」而不是「填完立刻」</h2>
 * <p>连续填充（一次右键填一整片）会在几十个容器之间连轴转，用的还是同一批材料 ——
 * 每个容器填完就退一次的话，下一个容器立刻又要回来，形成「退 → 要 → 退」的空转。
 * 所以账本<b>跨轮累积</b>，只在「补料链停了 {@value #IDLE_TICKS} tick 且手上没有容器界面」
 * 时才真正发一次包。</p>
 *
 * <h2>为什么不用「到货时间 + 热集」那套</h2>
 * <p>{@code litematica-printer} 那套 LRU + 热集是为「打印途中持续取料、背包一直紧」设计的：
 * 它必须每几 tick 就决定「现在退哪几格」。本模组的填充是<b>一口气跑完</b>的离散动作，
 * 结束时该退什么一目了然（就是需求侧算出来的余料），不需要在时间维度上做取舍。
 * 共用的只有那条安全红线：<b>整格覆盖才允许上交</b>（见 {@link WarehouseReturnModel#plan}）。</p>
 *
 * <h2>账本什么时候作废</h2>
 * <p>断线 / 换世界（{@link ClientPlayConnectionEvents#DISCONNECT}）——
 * 那是唯一确定「接下来看到的是另一份背包」的时刻。关填充开关、改配置、进出维度都不清：
 * 那些不换背包，清了只会把余料变成认不出的死重。</p>
 */
public final class WarehouseReturnQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /**
     * 补料链停下之后再过这么多 tick 才动手。
     *
     * <p>取 40（2 秒）：一轮 {@code applyTemplate} 加回执结算最多几十毫秒，
     * 2 秒足够把「同一个容器的下一轮补料」「连续填充的下一个容器」都等过去，
     * 又短到玩家松开右键后能立刻看到背包变干净。</p>
     */
    private static final long IDLE_TICKS = 40L;

    /** 一批最多等多久回执（毫秒）。50ms 限流 + 往返，2 秒足够。 */
    private static final long ACK_TIMEOUT_MS = 2000L;

    /** 两批之间至少隔几 tick（服务端限流是 50ms，这里给足 1 秒）。 */
    private static final long COOLDOWN_TICKS = 20L;

    /** 服务端明确拒绝（规则没开 / 包不合法）之后，多久之内不再尝试。 */
    private static final long REJECT_BACKOFF_TICKS = 200L;

    /** 一次填充会话最多发几批，防止某个坏状态把返还变成常开。 */
    private static final int MAX_ATTEMPTS = 6;

    private static boolean initialized;

    /** 请求流水号：正数递增，绕回时跳过 0（0 是「没有请求在飞」的哨兵）。 */
    private static int nextNonce = 1;

    private static volatile int activeNonce;
    private static volatile long sentAt;
    private static volatile int latestStatus = PrinterReturnResultPayload.NONE;
    private static volatile int latestReturned;
    /** 在飞那一批的物品 → 件数（回执只带总量，销账按这个顺序扣）。 */
    private static Map<String, Integer> inFlightBatch = Map.of();
    /**
     * 在飞那一批的槽位 → 件数。
     *
     * <p>🔴 回执接收器可能被 printer 抢先注册（Fabric 的 {@code registerGlobalReceiver}
     * 是<b>先注册者赢、后注册者返回 false 且不覆盖</b>，见 {@link #init}），那时我们收不到
     * {@code printer_return_result}。这批槽位号就是「背包差分兜底」的唯一依据：
     * 服务端确实把它们清空了 ⇒ 按实收销账，功能照样能用，只是少了那条提示。</p>
     */
    private static Map<Integer, Integer> inFlightSlotCounts = Map.of();

    /** 会话开始时手里各有多少件（只数「默认数据组件」的堆叠）。 */
    private static final Map<String, Integer> BASELINE = new LinkedHashMap<>();
    /** 账本：物品 id → 还欠着多少件没退回仓库。 */
    private static final Map<String, Integer> OWED = new LinkedHashMap<>();

    private static boolean sessionOpen;
    private static long lastActivityTick = Long.MIN_VALUE;
    private static long blockedUntilTick = Long.MIN_VALUE;
    private static int attempts;

    private WarehouseReturnQueue() {
    }

    /** 客户端初始化时调一次；registry 由入口按版本传入（26.x / 1.21.11 方法名不同）。 */
    public static void init(PayloadTypeRegistry<?> c2s, PayloadTypeRegistry<?> s2c) {
        if (initialized) {
            return;
        }
        PayloadChannels.register(c2s, "printer_return",
                PrinterReturnPayload.TYPE, PrinterReturnPayload.CODEC);
        PayloadChannels.register(s2c, "printer_return_result",
                PrinterReturnResultPayload.TYPE, PrinterReturnResultPayload.CODEC);
        try {
            // 🔴 Fabric 的规则是「先注册者赢」：同一通道已经有 handler 时这里返回 false，
            // 并且<b>不会</b>覆盖原来的。printer 与本模组同时装时很可能就是那种情况 ——
            // 那就收不到回执，改由 pollAck 里的「背包差分」确认结果，绝不把功能整个关掉。
            boolean ours = ClientPlayNetworking.registerGlobalReceiver(
                    PrinterReturnResultPayload.TYPE, WarehouseReturnQueue::receive);
            if (!ours) {
                LOGGER.info("[虚空仓库退料] 回执接收器已被别的模组占用（printer 在场时属正常）："
                        + "改用背包差分确认返还结果");
            }
        } catch (RuntimeException duplicate) {
            LOGGER.info("[虚空仓库退料] 回执接收端注册被拒（{}）⇒ 改用背包差分确认返还结果",
                    duplicate.toString());
        }
        ClientTickEvents.END_CLIENT_TICK.register(WarehouseReturnQueue::tick);
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> clear());
        initialized = true;
    }

    /** 对端认不认这条返还通道（旧服务端 / 纯原版服 ⇒ false）。 */
    public static boolean isSupported() {
        if (!initialized) {
            return false;
        }
        try {
            return ClientPlayNetworking.canSend(PrinterReturnPayload.TYPE)
                    && PayloadChannels.usable(PrinterReturnPayload.TYPE);
        } catch (RuntimeException noConnection) {
            return false;
        }
    }

    /**
     * 一次填充开始向仓库要货时调用（只在第一轮）。
     *
     * <p>这里记下<b>要货之前</b>手里各有多少件 —— 它是「余料不可能超过这个增量」那道夹子的基线
     * （见 {@link WarehouseReturnModel#returnable}）。基线只在会话的第一轮采：后面几轮
     * 手里已经混进了仓库的货，再采一次会把它们算成玩家自己的。</p>
     *
     * @param itemIds 这一轮要问仓库要的物品 id（来自投影容器的缺口）
     */
    public static void beginSession(Minecraft client, Collection<String> itemIds) {
        if (client == null || client.player == null || client.level == null
                || itemIds == null || itemIds.isEmpty()) {
            return;
        }
        long now = now(client);
        if (sessionOpen) {
            // 上一次会话还没收尾（连续填充的下一个容器）：基线绝不动，账继续往上加。
            // 这样整片容器填完之前不会退料，退的时候也是按「整片」算的总账。
            lastActivityTick = now;
            LOGGER.info("[虚空仓库退料] 连续填充的下一个容器：账继续累加，{}", describe());
            return;
        }
        BASELINE.clear();
        OWED.clear();
        attempts = 0;
        blockedUntilTick = Long.MIN_VALUE;
        for (String id : itemIds) {
            if (id == null || id.isBlank()) {
                continue;
            }
            BASELINE.put(id, countHeld(client, id));
        }
        sessionOpen = true;
        lastActivityTick = now;
        LOGGER.info("[虚空仓库退料] 开始记账：{} 种物品，基线 {}", BASELINE.size(), BASELINE);
    }

    /**
     * 每一轮补料结算后调一次：把「仓库整组给多了」的那部分记进账本。
     *
     * <p>余料 = {@code 到手件数 − 缺口件数}。缺口以内的都是填充真要用的，不算余料；
     * 仓库只补到一部分时（{@code 到手 < 缺口}）这里算出来是 0，也就一件都不退。</p>
     */
    public static void noteDelivered(List<WarehouseFillPlan.Delivery> report) {
        if (!sessionOpen || report == null || report.isEmpty()) {
            return;
        }
        for (WarehouseFillPlan.Delivery delivery : report) {
            if (delivery == null || delivery.itemId() == null || delivery.itemId().isBlank()) {
                continue;
            }
            if (delivery.need() <= 0) {
                // 需求未知（只有物品清单的旧入口）：说不出填充会用掉多少，就不退。
                continue;
            }
            int leftover = delivery.got() - delivery.need();
            if (leftover <= 0) {
                continue;
            }
            OWED.merge(delivery.itemId(), leftover, Integer::sum);
            LOGGER.info("[虚空仓库退料] 记账：{} 到手 {} 件、缺口 {} 件 ⇒ 余料 {} 件（账上合计 {} 件）",
                    delivery.itemId(), delivery.got(), delivery.need(), leftover,
                    WarehouseReturnModel.total(OWED));
        }
        lastActivityTick = now(Minecraft.getInstance());
    }

    /** 账上还欠着多少件（自查 / 日志用）。 */
    public static int pendingCount() {
        return WarehouseReturnModel.total(OWED);
    }

    /** 账上记着多少种物品（自查 / 日志用）。 */
    public static int pendingKinds() {
        return OWED.size();
    }

    /** 作废整本账（断线 / 换世界）。 */
    public static void clear() {
        BASELINE.clear();
        OWED.clear();
        sessionOpen = false;
        lastActivityTick = Long.MIN_VALUE;
        blockedUntilTick = Long.MIN_VALUE;
        attempts = 0;
        forgetRequest();
    }

    /**
     * 账本的可读快照，压成一行给日志与自查用（不放物品译名：这条要在无语言环境下也能跑）。
     */
    public static String describe() {
        if (!sessionOpen) {
            return "（没有进行中的填充会话）";
        }
        return "{账上=" + pendingCount() + "件/" + pendingKinds() + "种"
                + " 基线=" + BASELINE.size() + "种"
                + " 在飞=" + (activeNonce == 0 ? "无" : "nonce=" + activeNonce)
                + " 已试=" + attempts + "批}";
    }

    /**
     * 每客户端 tick 推进一次：等回执 → 等补料链安静 → 挑格 → 发包。
     *
     * <p>整条路径上「什么都不做」是常态（没有会话 / 还在要货 / 容器开着），
     * 所以最前面几条判据都只是几次字段比较。</p>
     */
    private static void tick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            clear();
            return;
        }
        if (activeNonce != 0) {
            pollAck(client, now(client));
            return;
        }
        if (!sessionOpen) {
            return;
        }
        if (WarehouseFillSupply.isActive()) {
            // 补料链还在跑（连续填充的下一个容器也在里面）⇒ 不让它安静下来
            lastActivityTick = now(client);
            return;
        }
        long now = now(client);
        if (lastActivityTick != Long.MIN_VALUE && now - lastActivityTick < IDLE_TICKS) {
            return;
        }
        if (OWED.isEmpty()) {
            closeSession("没有余料");
            return;
        }
        if (!isSupported()) {
            closeSession("服务端不支持返还通道");
            return;
        }
        if (now < blockedUntilTick) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            closeSession("重试次数用尽");
            return;
        }
        if (!client.player.containerMenu.equals(client.player.inventoryMenu)) {
            // 容器还开着：东西在玩家眼皮底下突然消失比多等一会儿更吓人。
            return;
        }
        Map<String, Integer> budget = budgetOf(client);
        if (budget.isEmpty()) {
            // 手里已经一件余料都不剩（填充用光了 / 玩家自己挪走了）⇒ 账自然销掉
            closeSession("背包里已无仓库余料");
            return;
        }
        List<WarehouseReturnModel.Slot> slots = WarehouseReturnModel.plan(
                budget, collectSlots(client), Set.of(), WarehouseReturnModel.MAIN_INVENTORY_SLOTS);
        if (slots.isEmpty()) {
            // 账上有数、但没有一格是「整格都归仓库」的。绝不硬退（那会把玩家的存货退走），
            // 记一条能对上号的日志后收尾；下一次填充会重新记账。
            closeSession("挑不出整格都是仓库余料的槽位");
            return;
        }
        send(client, slots, budget, now);
    }

    /** 发一批返还请求。 */
    private static void send(Minecraft client, List<WarehouseReturnModel.Slot> slots,
                             Map<String, Integer> budget, long now) {
        Map<String, Integer> batch = new LinkedHashMap<>();
        List<Integer> indexes = new ArrayList<>(slots.size());
        for (WarehouseReturnModel.Slot slot : slots) {
            indexes.add(slot.index());
            batch.merge(slot.itemId(), slot.count(), Integer::sum);
        }
        int nonce = nextNonce;
        nextNonce = nonce >= Integer.MAX_VALUE ? 1 : nonce + 1;
        activeNonce = nonce;
        latestStatus = PrinterReturnResultPayload.NONE;
        latestReturned = 0;
        inFlightBatch = Collections.unmodifiableMap(new LinkedHashMap<>(batch));
        Map<Integer, Integer> slotCounts = new LinkedHashMap<>();
        for (WarehouseReturnModel.Slot slot : slots) {
            slotCounts.put(slot.index(), slot.count());
        }
        inFlightSlotCounts = Collections.unmodifiableMap(slotCounts);
        attempts++;
        try {
            ClientPlayNetworking.send(new PrinterReturnPayload(nonce, List.copyOf(indexes)));
        } catch (RuntimeException failed) {
            LOGGER.warn("[虚空仓库退料] 发包失败：{}", failed.toString());
            forgetRequest();
            blockedUntilTick = now + COOLDOWN_TICKS;
            return;
        }
        sentAt = System.currentTimeMillis();
        LOGGER.info("[虚空仓库退料] 已发出第 {} 批：槽位 {} ⇒ {} 件（账上 {} 件，可退上限 {}）",
                attempts, indexes, WarehouseReturnModel.total(batch),
                WarehouseReturnModel.total(OWED), WarehouseReturnModel.total(budget));
    }

    /**
     * 回执到了没有；到了就销账。
     *
     * <p>🔴 这里还有一条兜底：printer 同时在场时可能抢走同一个回执接收器（见 {@link #init}）。
     * 那种情况下收不到回执，但<b>服务端照样把东西收走了</b> —— 所以超时时要看一眼那几格是不是
     * 真的空了。用背包差分判成功，与取货侧「成功要取交集」是同一条纪律。</p>
     */
    private static void pollAck(Minecraft client, long now) {
        int status = latestStatus;
        if (status == PrinterReturnResultPayload.NONE) {
            if (System.currentTimeMillis() - sentAt > ACK_TIMEOUT_MS) {
                int gone = clearedCount(client);
                if (gone > 0) {
                    settle(client, inFlightBatch, gone, "没收到回执但背包差分确认已清空");
                } else {
                    // 真的一格没动：不销账（服务端可能还在忙），下一轮按背包现状重新挑
                    LOGGER.warn("[虚空仓库退料] nonce={} 超时：没有回执，那 {} 格也还在 → 这批不销账，"
                            + "稍后重挑", activeNonce, inFlightSlotCounts.size());
                }
                forgetRequest();
                blockedUntilTick = now + COOLDOWN_TICKS;
            }
            return;
        }
        Map<String, Integer> batch = inFlightBatch;
        if (status == PrinterReturnResultPayload.OK) {
            int returned = latestReturned;
            settle(client, batch, returned, "服务端回执 OK");
            blockedUntilTick = now + COOLDOWN_TICKS;
        } else if (status == PrinterReturnResultPayload.THROTTLED) {
            blockedUntilTick = now + COOLDOWN_TICKS;
            LOGGER.info("[虚空仓库退料] 撞限流，{} tick 后重试", COOLDOWN_TICKS);
        } else if (status == PrinterReturnResultPayload.RULE_OFF) {
            blockedUntilTick = now + REJECT_BACKOFF_TICKS;
            LOGGER.warn("[虚空仓库退料] 服务端规则没开（voidWarehouse / voidWarehouseRestock）⇒ "
                    + "{} tick 后再试", REJECT_BACKOFF_TICKS);
        } else if (status == PrinterReturnResultPayload.NOTHING) {
            // 服务端一格都没收（槽位全被跳过）。再发同一批只会一直撞同一堵墙，
            // 按「这批退不了」销账，别把它变成每秒一次的常开重试。
            OWED.clear();
            OWED.putAll(WarehouseReturnModel.deduct(OWED, batch,
                    WarehouseReturnModel.total(batch)));
            LOGGER.warn("[虚空仓库退料] 服务端一格都没收（潜影盒 / 空槽全被跳过）⇒ 这批销账，剩余 {} 件",
                    pendingCount());
        } else {
            blockedUntilTick = now + REJECT_BACKOFF_TICKS;
            LOGGER.warn("[虚空仓库退料] 服务端回执 status={} ⇒ {} tick 后再试", status, REJECT_BACKOFF_TICKS);
        }
        forgetRequest();
    }

    /** 销账 + 提示：回执与背包差分两条路共用，避免两份口径。 */
    private static void settle(Minecraft client, Map<String, Integer> batch, int returned,
                               String evidence) {
        OWED.clear();
        OWED.putAll(WarehouseReturnModel.deduct(OWED, batch, returned));
        announce(client, batch, returned);
        LOGGER.info("[虚空仓库退料] {}，实收 {} 件；账上还剩 {} 件 / {} 种",
                evidence, returned, pendingCount(), pendingKinds());
    }

    /** 在飞的那几格里，现在已经空掉的原先装了多少件（回执被别人抢走时的兜底判据）。 */
    private static int clearedCount(Minecraft client) {
        if (client == null || client.player == null || inFlightSlotCounts.isEmpty()) {
            return 0;
        }
        var inventory = client.player.getInventory();
        int size = Math.min(WarehouseReturnModel.MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int total = 0;
        for (Map.Entry<Integer, Integer> entry : inFlightSlotCounts.entrySet()) {
            int index = entry.getKey() == null ? -1 : entry.getKey();
            if (index < 0 || index >= size) {
                continue;
            }
            if (inventory.getItem(index).isEmpty()) {
                total += entry.getValue() == null ? 0 : entry.getValue();
            }
        }
        return total;
    }

    /** 退成功就在动作栏说一句：退了什么、各多少。 */
    private static void announce(Minecraft client, Map<String, Integer> batch, int returned) {
        if (client == null || client.player == null || batch.isEmpty() || returned <= 0) {
            return;
        }
        try {
            MutableComponent list = Component.empty();
            int shown = 0;
            int remaining = returned;
            for (Map.Entry<String, Integer> entry : batch.entrySet()) {
                if (remaining <= 0) {
                    break;
                }
                if (shown >= 4) {
                    list.append(Component.literal("…"));
                    break;
                }
                int count = Math.min(entry.getValue() == null ? 0 : entry.getValue(), remaining);
                if (count <= 0) {
                    continue;
                }
                if (shown > 0) {
                    list.append(Component.literal("、"));
                }
                list.append(WarehouseText.key(nameOf(entry.getKey()) + " ×" + count));
                remaining -= count;
                shown++;
            }
            client.player.displayClientMessage(WarehouseText.info(client,
                    Component.translatable("message.neko_client.warehouse_return_done", list)), false);
        } catch (RuntimeException ignored) {
            // 提示是附带的：拼文本出任何问题都只吞掉，绝不能影响「东西已经退回仓库了」这件事
        }
    }

    /** 物品 id → 可读名字；解析不出来就退回 id 本身（绝不留空）。 */
    private static String nameOf(String itemId) {
        Identifier key = itemId == null ? null : Identifier.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return itemId == null ? "?" : itemId;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(key)).getHoverName().getString();
    }

    /**
     * 按背包现状算出「每种物品这次最多退多少件」。
     *
     * <p>两道夹子都在 {@link WarehouseReturnModel#returnable} 里：需求侧（账本）
     * 与持有侧（现在比开局多了多少）。这里只负责把两个数字量出来。</p>
     */
    private static Map<String, Integer> budgetOf(Minecraft client) {
        Map<String, Integer> budget = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : OWED.entrySet()) {
            int held = countHeld(client, entry.getKey());
            int can = WarehouseReturnModel.returnable(entry.getValue(),
                    BASELINE.getOrDefault(entry.getKey(), 0), held);
            if (can > 0) {
                budget.put(entry.getKey(), can);
            }
        }
        return budget;
    }

    /**
     * 扫一遍背包，挑出<b>有可能</b>上交的格子（还没做账本核对）。
     *
     * <p>三类整格跳过：① 玩家当前选中的那一格（手里的东西突然没了最扎眼）；
     * ② 潜影盒（服务端整类拒绝，而且正常流程里仓库根本不会把盒子发给填充）；
     * ③ 带数据组件的堆叠（附魔书、命名物品…）—— 仓库发出来的永远是<b>默认数据组件</b>那一份，
     * 带组件的同种物品一定是玩家自己的。</p>
     */
    private static List<WarehouseReturnModel.Slot> collectSlots(Minecraft client) {
        var inventory = client.player.getInventory();
        int size = Math.min(WarehouseReturnModel.MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int selected = inventory.getSelectedSlot();
        int hotbar = Math.min(WarehouseReturnModel.HOTBAR_SLOTS, size);
        List<WarehouseReturnModel.Slot> slots = new ArrayList<>();
        for (int index : WarehouseReturnModel.scanOrder(size, hotbar)) {
            if (index == selected) {
                continue;
            }
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty() || isShulkerBox(stack)) {
                continue;
            }
            if (!ItemStack.isSameItemSameComponents(stack, new ItemStack(stack.getItem()))) {
                continue;
            }
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null) {
                continue;
            }
            slots.add(new WarehouseReturnModel.Slot(index, key.toString(), stack.getCount()));
        }
        return slots;
    }

    /** 数主背包 36 格里「默认数据组件」的那一份该物品有多少件（与服务端口径一致）。 */
    private static int countHeld(Minecraft client, String itemId) {
        if (client == null || client.player == null) {
            return 0;
        }
        ItemStack sample = stackOf(itemId);
        if (sample.isEmpty()) {
            return 0;
        }
        var inventory = client.player.getInventory();
        int size = Math.min(WarehouseReturnModel.MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int total = 0;
        for (int slot = 0; slot < size; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && ItemStack.isSameItemSameComponents(stack, sample)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    private static ItemStack stackOf(String itemId) {
        if (itemId == null) {
            return ItemStack.EMPTY;
        }
        Identifier key = Identifier.tryParse(itemId);
        if (key == null || !BuiltInRegistries.ITEM.containsKey(key)) {
            return ItemStack.EMPTY;
        }
        return new ItemStack(BuiltInRegistries.ITEM.getValue(key));
    }

    /** 与服务端 {@code PrinterReturnService#isBlacklisted} 同一判据：看物品背后的方块类型。 */
    private static boolean isShulkerBox(ItemStack stack) {
        return stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof ShulkerBoxBlock;
    }

    private static void closeSession(String reason) {
        if (!sessionOpen) {
            return;
        }
        int left = pendingCount();
        LOGGER.info("[虚空仓库退料] 会话结束（{}）：账上剩 {} 件 / {} 种未退回",
                reason, left, pendingKinds());
        BASELINE.clear();
        OWED.clear();
        sessionOpen = false;
        lastActivityTick = Long.MIN_VALUE;
        forgetRequest();
    }

    private static void forgetRequest() {
        activeNonce = 0;
        latestStatus = PrinterReturnResultPayload.NONE;
        latestReturned = 0;
        inFlightBatch = Map.of();
        inFlightSlotCounts = Map.of();
    }

    /** 收到回执：只认自己那一批，迟到的（nonce 对不上）一概丢掉。 */
    private static void receive(PrinterReturnResultPayload payload, ClientPlayNetworking.Context context) {
        if (payload == null || payload.nonce() <= 0 || payload.nonce() != activeNonce) {
            return;
        }
        latestStatus = payload.status();
        latestReturned = payload.returned();
    }

    private static long now(Minecraft client) {
        if (client == null || client.level == null) {
            return Long.MIN_VALUE;
        }
        return client.level.getGameTime();
    }
}
