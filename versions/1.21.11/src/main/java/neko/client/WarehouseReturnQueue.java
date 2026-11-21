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
 * <h2>账本为什么<b>跨填充</b>存活</h2>
 * <p>0.5.7 第一版是「一次填充一个会话」：开填采基线、填完结算、会话结束清空。真机上这么写会漏账 ——
 * 只要那次填充<b>没有在同一轮里要货</b>（例如上一轮已经把材料取到手了，这一轮纯用背包里的货填完），
 * 就没采基线、账本里也没有这件东西，余料永远躺在背包里。所以现在：</p>
 * <ul>
 *   <li>基线<b>一旦为该物品记下就不再改</b>（{@code putIfAbsent}），跨填充沿用；</li>
 *   <li>账本只在「退回」或「确认用掉」时减少，不按会话清空；</li>
 *   <li>「确认用掉」的判据是<b>背包里已经不多于基线</b>（{@link #pruneConsumed}）——
 *       这一条同时兜住「玩家拿去用在别处」「放进箱子」两种走法，账本不会永远挂着。</li>
 * </ul>
 *
 * <h2>什么时候动手</h2>
 * <p>补料链停下 {@value #IDLE_TICKS} tick 且手上没有容器界面时才发一批。连续填充在几十个
 * 容器之间连轴转时账一路累加，整片填完才退一次 —— 每个容器退一次会变成「退 → 要 → 退」空转。</p>
 *
 * <h2>🔴 回执可能到不了</h2>
 * <p>{@code printer_return_result} 这条通道 {@code litematica-printer} 抢在前面注册
 * （Fabric 先注册者赢，初始化顺序按 mod id 字典序）。所以这里用
 * {@link PayloadReceivers#attach} 接管并转发；万一还是收不到，超时后用<b>背包差分</b>确认
 * （那几格真的空了就算成交）—— 与取货侧「成功要取交集」是同一条纪律。</p>
 */
public final class WarehouseReturnQueue {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /**
     * 补料链停下之后再过这么多 tick 才动手。
     *
     * <p>取 60（3 秒）：一轮 {@code applyTemplate} 加回执结算最多几十毫秒，3 秒足够把
     * 「同一个容器的下一轮补料」「连续填充的下一个容器」都等过去，也给玩家留出
     * 「刚关掉箱子又想再填一次」的余地 —— 太短会让刚取出来的材料立刻被退回去。</p>
     */
    private static final long IDLE_TICKS = 60L;

    /** 一批最多等多久回执（毫秒）。50ms 限流 + 往返，2 秒足够。 */
    private static final long ACK_TIMEOUT_MS = 2000L;

    /** 两批之间至少隔几 tick（服务端限流是 50ms，这里给足 1 秒）。 */
    private static final long COOLDOWN_TICKS = 20L;

    /** 服务端明确拒绝（规则没开 / 包不合法 / 挑不出整格）之后，多久之内不再尝试。 */
    private static final long REJECT_BACKOFF_TICKS = 200L;

    /** 一轮补料里最多发几批，防止某个坏状态把返还变成常开。收到新货会重置。 */
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
    /** 在飞那一批的槽位 → 件数（回执收不到时靠它做背包差分，见 {@link #pollAck}）。 */
    private static Map<Integer, Integer> inFlightSlotCounts = Map.of();

    /** 物品 id → 第一次为它要货<b>之前</b>手里有多少件。记下就不再改（跨填充沿用）。 */
    private static final Map<String, Integer> BASELINE = new LinkedHashMap<>();
    /**
     * 账本：物品 id → 仓库给过、还没退回的件数。
     *
     * <p>🔴 刻意<b>不是 final</b>：每次记账 / 销账都是「算出一份新的，然后整体替换」。
     * 0.5.7 真机上栽过一次 —— 写成 {@code OWED.clear(); OWED.putAll(add(OWED, …))}
     * 时 {@code add} 读到的是已经清空的表，等于每次都用空账本重算，历史余额全丢：
     * 日志里「账上合计 128 件」下一分钟变成 64 件，玻璃板的余料再也没人退。
     * 换成整体替换之后，那种写法根本写不出来。</p>
     */
    private static Map<String, Integer> OWED = new LinkedHashMap<>();

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
        // 🔴 接管而不是「注册失败就算了」：printer 的 mod id 排在前面，永远先注册这条回执通道。
        if (PayloadReceivers.attach(PrinterReturnResultPayload.TYPE, WarehouseReturnQueue::receive)) {
            LOGGER.info("[虚空仓库退料] 回执接收器原先被别的模组占用（printer 在场时属正常）：已接管并转发，两边都收得到");
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
     * <p>只为<b>还没记过</b>的物品采基线，而且必须发生在要货之前 —— 调用点就在
     * {@code WarehouseFillSupply.begin} 前面。已经记过的物品沿用旧基线，
     * 那才是「这一批仓库贡献了多少」的正确起点。</p>
     *
     * @param itemIds 这一轮要问仓库要的物品 id（来自投影容器的缺口）
     */
    public static void beginSession(Minecraft client, Collection<String> itemIds) {
        if (client == null || client.player == null || client.level == null
                || itemIds == null || itemIds.isEmpty()) {
            return;
        }
        lastActivityTick = now(client);
        boolean added = false;
        for (String id : itemIds) {
            if (id == null || id.isBlank() || BASELINE.containsKey(id)) {
                continue;
            }
            BASELINE.put(id, countHeld(client, id));
            added = true;
        }
        if (added) {
            LOGGER.info("[虚空仓库退料] 记基线：{} 种 {}", BASELINE.size(), BASELINE);
        }
    }

    /**
     * 每一轮补料结算后调一次：把<b>仓库这一轮给了多少</b>记进账本。
     *
     * <p>🔴 记的是到手件数，不是「到手 − 缺口」。按后者记的前提是「缺口以内的都被填充用掉了」，
     * 一旦那一轮没续填（容器被关 / 回执丢了）前提就破产，余料永远退不掉 —— 真机日志里
     * `取走 64 个 铁锭` 后面跟着 `账上 0 件`，就是这个写法留下的坑。</p>
     */
    public static void noteDelivered(List<WarehouseFillPlan.Delivery> report) {
        if (report == null || report.isEmpty()) {
            return;
        }
        Map<String, Integer> delivered = WarehouseReturnModel.deliveredOf(report);
        if (delivered.isEmpty()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        for (String id : delivered.keySet()) {
            if (!BASELINE.containsKey(id)) {
                // 基线没采到（理论上不会）：用「现在手里 − 刚到手」反推，绝不按 0 算
                BASELINE.put(id, Math.max(0, countHeld(client, id) - delivered.get(id)));
            }
        }
        OWED = WarehouseReturnModel.add(OWED, delivered);
        attempts = 0;
        blockedUntilTick = Long.MIN_VALUE;
        lastActivityTick = now(client);
        for (Map.Entry<String, Integer> entry : delivered.entrySet()) {
            LOGGER.info("[虚空仓库退料] 记账：{} 到手 {} 件（账上合计 {} 件）",
                    entry.getKey(), entry.getValue(), WarehouseReturnModel.total(OWED));
        }
    }

    /**
     * 有<b>任何</b>填充动作就推一下「最近活动」，不管那一轮要不要取料。
     *
     * <p>为什么不能只靠 {@link #noteDelivered}：不需要补料的那次填充（背包里已经够了）
     * 一件货都不要，账本自然没有新事件，空闲计时就会一路走到底 —— 玩家连着填第二个容器时，
     * 余料会在他正填的时候被退走，然后下一轮又要重新取。真机日志里 14:11:58 那次
     * 「开箱但没有取货」正是这个情形。</p>
     */
    public static void noteActivity() {
        Minecraft client = Minecraft.getInstance();
        if (client != null && client.level != null) {
            lastActivityTick = now(client);
        }
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
        OWED = new LinkedHashMap<>();
        lastActivityTick = Long.MIN_VALUE;
        blockedUntilTick = Long.MIN_VALUE;
        attempts = 0;
        forgetRequest();
    }

    /** 账本的可读快照，压成一行给日志与自查用（不放物品译名：这条要在无语言环境下也能跑）。 */
    public static String describe() {
        if (OWED.isEmpty()) {
            return "（账本为空）";
        }
        return "{账上=" + pendingCount() + "件/" + pendingKinds() + "种"
                + " 基线=" + BASELINE.size() + "种"
                + " 在飞=" + (activeNonce == 0 ? "无" : "nonce=" + activeNonce)
                + " 已试=" + attempts + "批}";
    }

    /**
     * 每客户端 tick 推进一次：等回执 → 等补料链安静 → 结算已用完的 → 挑格 → 发包。
     *
     * <p>「什么都不做」是常态（账本空 / 还在要货 / 容器开着），最前面几条判据都只是字段比较。</p>
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
        if (OWED.isEmpty()) {
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
        if (!isSupported()) {
            LOGGER.info("[虚空仓库退料] 服务端不支持返还通道 ⇒ 放弃这本账（{}）", describe());
            clear();
            return;
        }
        if (now < blockedUntilTick) {
            return;
        }
        if (!client.player.containerMenu.equals(client.player.inventoryMenu)) {
            // 容器还开着：东西在玩家眼皮底下突然消失比多等一会儿更吓人。
            return;
        }
        pruneConsumed(client);
        if (OWED.isEmpty()) {
            return;
        }
        if (attempts >= MAX_ATTEMPTS) {
            LOGGER.info("[虚空仓库退料] 已试 {} 批仍未退成 ⇒ 放弃这本账（{}）", attempts, describe());
            clear();
            return;
        }
        Map<String, Integer> budget = budgetOf(client);
        if (budget.isEmpty()) {
            pruneAll();
            return;
        }
        List<WarehouseReturnModel.Slot> slots = WarehouseReturnModel.plan(
                budget, collectSlots(client), Set.of(), WarehouseReturnModel.MAIN_INVENTORY_SLOTS);
        if (slots.isEmpty()) {
            // 账上有数、但没有一格是「整格都归仓库」的。绝不硬退（那会把玩家的存货退走）。
            LOGGER.info("[虚空仓库退料] 挑不出整格都是仓库余料的槽位：可退上限 {} 件，背包里同种格子 {} ⇒ {}",
                    WarehouseReturnModel.total(budget), describeSameItems(client, budget), describe());
            blockedUntilTick = now + REJECT_BACKOFF_TICKS;
            attempts++;
            return;
        }
        send(client, slots, budget, now);
    }

    /** 发一批返还请求。 */
    private static void send(Minecraft client, List<WarehouseReturnModel.Slot> slots,
                             Map<String, Integer> budget, long now) {
        Map<String, Integer> batch = new LinkedHashMap<>();
        Map<Integer, Integer> slotCounts = new LinkedHashMap<>();
        List<Integer> indexes = new ArrayList<>(slots.size());
        for (WarehouseReturnModel.Slot slot : slots) {
            indexes.add(slot.index());
            slotCounts.put(slot.index(), slot.count());
            batch.merge(slot.itemId(), slot.count(), Integer::sum);
        }
        int nonce = nextNonce;
        nextNonce = nonce >= Integer.MAX_VALUE ? 1 : nonce + 1;
        activeNonce = nonce;
        latestStatus = PrinterReturnResultPayload.NONE;
        latestReturned = 0;
        inFlightBatch = Collections.unmodifiableMap(new LinkedHashMap<>(batch));
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
     * <p>🔴 兜底：printer 在场时回执可能收不到（见类注释）。超时后看一眼那几格是不是真的空了 ——
     * 空了就按实收销账，物品确实已经回仓库了，只是没人告诉我们。</p>
     */
    private static void pollAck(Minecraft client, long now) {
        int status = latestStatus;
        if (status == PrinterReturnResultPayload.NONE) {
            if (System.currentTimeMillis() - sentAt > ACK_TIMEOUT_MS) {
                int gone = clearedCount(client);
                if (gone > 0) {
                    settle(client, inFlightBatch, gone, "没收到回执但背包差分确认已清空");
                } else {
                    LOGGER.warn("[虚空仓库退料] nonce={} 超时：没有回执，那 {} 格也还在 → 这批不销账，稍后重挑",
                            activeNonce, inFlightSlotCounts.size());
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
            OWED = WarehouseReturnModel.deduct(OWED, batch,
                    WarehouseReturnModel.total(batch));
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
        OWED = WarehouseReturnModel.deduct(OWED, batch, returned);
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
     * 把「手里已经不多于基线」的物品从账本里去掉 —— 那部分确实被用掉了（或者被玩家挪走了）。
     *
     * <p>这一条是账本能自己清干净的保证：没有它，一件被消费掉的物品会永远挂着基线，
     * 之后玩家自己捡到同种物品反而会被当成仓库的货退走。</p>
     */
    private static void pruneConsumed(Minecraft client) {
        if (OWED.isEmpty()) {
            return;
        }
        List<String> consumed = new ArrayList<>();
        for (String id : OWED.keySet()) {
            int held = countHeld(client, id);
            int baseline = BASELINE.getOrDefault(id, 0);
            if (held <= baseline) {
                consumed.add(id);
            }
        }
        for (String id : consumed) {
            Integer owed = OWED.remove(id);
            BASELINE.remove(id);
            LOGGER.info("[虚空仓库退料] {} 已回到基线（手里没有余料）⇒ 销账 {} 件", id, owed);
        }
        if (!consumed.isEmpty() && OWED.isEmpty()) {
            LOGGER.info("[虚空仓库退料] 账本已清空");
        }
    }

    /** 整本账作废（放弃 / 服务端不支持）。 */
    private static void pruneAll() {
        if (OWED.isEmpty() && BASELINE.isEmpty()) {
            return;
        }
        LOGGER.info("[虚空仓库退料] 放弃这本账：{}", describe());
        OWED = new LinkedHashMap<>();
        BASELINE.clear();
    }

    /**
     * 按背包现状算出「每种物品这次最多退多少件」。
     *
     * <p>两道夹子都在 {@link WarehouseReturnModel#returnable} 里：需求侧（账本）
     * 与持有侧（现在比基线多了多少）。这里只负责把两个数字量出来。</p>
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
     * <p>两类整格跳过：① 潜影盒（服务端整类拒绝，而且正常流程里仓库根本不会把盒子发给填充）；
     * ② 带数据组件的堆叠（附魔书、命名物品…）—— 仓库发出来的永远是<b>默认数据组件</b>那一份，
     * 带组件的同种物品一定是玩家自己的。</p>
     *
     * <p>🔴 <b>不跳过玩家选中的那一格</b>：服务端 {@code PrinterTakeService#tryGive} 是从
     * slot 0 起找空位的，货几乎总是先落在快捷栏第一格，而那一格默认就是玩家选中的格子。
     * 第一版学 printer「跳过选中格」，结果最大的那堆余料永远挑不出来 —— 真机上表现为
     * 「填充完了东西还在背包里」。填充这时已经结束，手里的东西该回仓库就回仓库。</p>
     */
    private static List<WarehouseReturnModel.Slot> collectSlots(Minecraft client) {
        var inventory = client.player.getInventory();
        int size = Math.min(WarehouseReturnModel.MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        int hotbar = Math.min(WarehouseReturnModel.HOTBAR_SLOTS, size);
        List<WarehouseReturnModel.Slot> slots = new ArrayList<>();
        for (int index : WarehouseReturnModel.scanOrder(size, hotbar)) {
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

    /** 「背包里这些物品各有多少格、各多少件」，只给「挑不出整格」那条诊断日志用。 */
    private static String describeSameItems(Minecraft client, Map<String, Integer> budget) {
        if (client == null || client.player == null || budget.isEmpty()) {
            return "（无）";
        }
        var inventory = client.player.getInventory();
        int size = Math.min(WarehouseReturnModel.MAIN_INVENTORY_SLOTS, inventory.getContainerSize());
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (int index = 0; index < size; index++) {
            ItemStack stack = inventory.getItem(index);
            if (stack.isEmpty()) {
                continue;
            }
            Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
            if (key == null || !budget.containsKey(key.toString())) {
                continue;
            }
            counts.merge(key + "[" + index + "]", stack.getCount(), Integer::sum);
        }
        return counts.isEmpty() ? "（无）" : counts.toString();
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
