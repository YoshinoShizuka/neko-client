package neko.client;

import neko.client.fill.WarehouseFillPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 投影填充用剩的仓库余料「该退多少、退哪几格」的<b>纯判据</b>：不引用 Minecraft / malilib。
 *
 * <h2>这条路上唯一危险的判断</h2>
 * <p>返还通道（{@code printer_return}）只收<b>槽位号</b>，服务端会把那一<b>整格</b>收进仓库 ——
 * 它没有「只收 37 个」这种说法。而背包里同一格完全可能是「玩家自己的 64 个」，
 * 或者「仓库的 40 个下面垫着玩家的 24 个」。所以本类只有一条铁律：
 * <b>一格只有在账本完整覆盖它的堆叠数时才允许上交</b>（{@link #plan}）。
 * 宁可少退，也绝不把玩家的存货退走。</p>
 *
 * <h2>账本为什么要有两道夹子</h2>
 * <p>「这次从仓库拿了 64 个、填充只要 20 个，所以剩 44」是<b>需求侧</b>的算法
 * （{@link #returnable} 的第一个参数，由调用方按「到手件数 − 缺口件数」算出来）。
 * 它精确，但只对「填充真的把缺口填满了」成立：填充中途失败、容器被锁、模板换了，
 * 实际消耗会比缺口少，于是「算出来的余料」比真正剩的多。</p>
 *
 * <p>所以还有第二道夹子 —— <b>持有侧</b>：余料不可能超过「现在手里这件东西比开局多了多少」
 * （{@code heldNow - heldBefore}）。两道夹子取小，就同时挡住了两个方向的误判：</p>
 * <ul>
 *   <li>需求侧偏大（填充失败）⇒ 被持有侧夹住，不会把没退成的东西再退一遍；</li>
 *   <li>持有侧偏大（玩家自己又捡了同种物品）⇒ 被需求侧夹住，最多退掉仓库给的那一份。</li>
 * </ul>
 *
 * <h2>为什么不能直接「把仓库给的全退回去」</h2>
 * <p>因为填充<b>用掉的</b>那部分是正当消耗。真按「全退」做，退的件数会超过背包里实际
 * 剩下的，最终表现是「退了 64、背包少了 64、仓库只多了 44」—— 差的 20 个凭空消失。
 * 服务端只按整格收，多出来的部分它也没得补。</p>
 */
public final class WarehouseReturnModel {

    /**
     * 主背包 + 快捷栏的槽位数。
     *
     * <p>与服务端 {@code PrinterReturnService#MAIN_INVENTORY_SLOTS} 的口径必须一致 ——
     * 不一致就会「客户端数第 30 格、服务端只看前 27 格」，退了个寂寞。</p>
     */
    public static final int MAIN_INVENTORY_SLOTS = 36;

    /** 快捷栏格数（扫描顺序里排在最后，见 {@link #scanOrder}） */
    public static final int HOTBAR_SLOTS = 9;

    /**
     * 背包里一格的内容。
     *
     * @param index  槽位号（0–35）
     * @param itemId 注册名（{@code minecraft:dirt}）
     * @param count  件数
     */
    public record Slot(int index, String itemId, int count) {

        public Slot {
            itemId = itemId == null ? "" : itemId;
        }

        /** 这一格能不能参与挑格（无效下标 / 空物品 / 0 件都不行） */
        public boolean usable() {
            return this.index >= 0 && this.count > 0 && !this.itemId.isEmpty();
        }
    }

    private WarehouseReturnModel() {
    }

    /**
     * 这种物品这次「能退多少件」。
     *
     * @param expectedLeft 需求侧算出来的余料（到手件数 − 缺口件数）；{@code <= 0} = 没有余料
     * @param heldBefore   本次填充<b>开始向仓库要货之前</b>手里有多少件
     * @param heldNow      现在手里有多少件（只数「默认数据组件」的那些，见调用方）
     * @return 可退件数；两道夹子里小的那个
     */
    public static int returnable(int expectedLeft, int heldBefore, int heldNow) {
        if (expectedLeft <= 0) {
            return 0;
        }
        int gained = heldNow - Math.max(0, heldBefore);
        if (gained <= 0) {
            // 手里一件都没多出来：要么填充用光了，要么玩家自己挪走了。都不能退。
            return 0;
        }
        return Math.min(expectedLeft, gained);
    }

    /**
     * 扫描背包的顺序：<b>先主背包（35→9），再快捷栏（8→0）</b>。
     *
     * <p>两个理由：① 快捷栏是打印机 / 填充取料的落点，能不动就不动；
     * ② 主背包靠后的格子通常是填充时临时塞进去的，先退它们对玩家扰动最小。</p>
     *
     * @param size        背包实际格数（取 {@code min(36, containerSize)}）
     * @param hotbarSlots 其中快捷栏占几格（取 {@code min(9, size)}）
     */
    public static List<Integer> scanOrder(int size, int hotbarSlots) {
        if (size <= 0) {
            return List.of();
        }
        int hotbar = Math.max(0, Math.min(hotbarSlots, size));
        List<Integer> order = new ArrayList<>(size);
        for (int index = size - 1; index >= hotbar; index--) {
            order.add(index);
        }
        for (int index = hotbar - 1; index >= 0; index--) {
            order.add(index);
        }
        return List.copyOf(order);
    }

    /**
     * 挑出这一批要交还给仓库的槽位。
     *
     * <p>贪心，但只有一条判据：<b>账本剩下的件数覆盖得住这一整格</b>。覆盖不住的（哪怕只差 1 个）
     * 一律跳过 —— 协议只能整格上交，把「玩家的 64 + 仓库的 40」那格交出去就等于白送 64 个。</p>
     *
     * @param budget   物品 id → 这次最多退多少件（顺序即优先级，见 {@link #returnable}）
     * @param slots    背包里的格子，<b>已按 {@link #scanOrder} 排好序</b>
     * @param skip     额外不许动的槽位（通常是玩家当前选中的那一格）
     * @param maxSlots 这一批最多挑几格（服务端一次最多收 36 格）
     * @return 按挑选顺序排列的槽位；空清单 = 没有一格能安全上交
     */
    public static List<Slot> plan(Map<String, Integer> budget, List<Slot> slots,
                                  Set<Integer> skip, int maxSlots) {
        if (budget == null || budget.isEmpty() || slots == null || slots.isEmpty() || maxSlots <= 0) {
            return List.of();
        }
        Map<String, Integer> left = new LinkedHashMap<>();
        for (Map.Entry<String, Integer> entry : budget.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                left.put(entry.getKey(), entry.getValue());
            }
        }
        if (left.isEmpty()) {
            return List.of();
        }
        List<Slot> picked = new ArrayList<>();
        for (Slot slot : slots) {
            if (picked.size() >= maxSlots) {
                break;
            }
            if (slot == null || !slot.usable()) {
                continue;
            }
            if (skip != null && skip.contains(slot.index())) {
                continue;
            }
            Integer remaining = left.get(slot.itemId());
            if (remaining == null || remaining < slot.count()) {
                // 🔴 账本没完整覆盖这一格 ⇒ 跳过。这里放行一次就是把玩家的存货退走。
                continue;
            }
            picked.add(slot);
            left.put(slot.itemId(), remaining - slot.count());
        }
        return List.copyOf(picked);
    }

    /**
     * 服务端实收 {@code returned} 件之后，账本怎么减。
     *
     * <p>回执只带<b>总件数</b>（逐物品明细走另一条通道，本模组不收），所以按这一批里
     * 各物品的件数<b>顺序扣</b>。近似是允许的：下一次挑格前会用背包实际持有量重新夹一遍
     * （{@link #returnable}），误差活不到下一批。</p>
     *
     * <p>{@code returned <= 0}（一格都没退成）时返回账本的副本，一件都不销 ——
     * 那时候东西还在背包里，销了账就再也退不掉了。</p>
     *
     * @return 新的账本（不修改入参）
     */
    public static Map<String, Integer> deduct(Map<String, Integer> owed, Map<String, Integer> batch,
                                             int returned) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (owed != null) {
            for (Map.Entry<String, Integer> entry : owed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (batch == null || batch.isEmpty() || returned <= 0) {
            return out;
        }
        int left = returned;
        for (Map.Entry<String, Integer> entry : batch.entrySet()) {
            if (left <= 0) {
                break;
            }
            String itemId = entry.getKey();
            int fromBatch = entry.getValue() == null ? 0 : entry.getValue();
            if (itemId == null || fromBatch <= 0) {
                continue;
            }
            Integer pending = out.get(itemId);
            if (pending == null) {
                continue;
            }
            int take = Math.min(fromBatch, left);
            int remaining = pending - take;
            if (remaining <= 0) {
                out.remove(itemId);
            } else {
                out.put(itemId, remaining);
            }
            left -= take;
        }
        return out;
    }

    /**
     * 把「仓库这一轮给了多少」累加进账本（逐物品相加）。
     *
     * <p>🔴 记的是<b>到手件数</b>，不是「到手 − 缺口」。0.5.7 第一版按「到手 − 缺口」记，
     * 真机上直接翻车：那一版自动填充在补料回执到达前就把容器关了，于是<b>续填根本没发生</b> ——
     * 「缺口以内的都算用掉了」这个前提不成立，账上只记下 23 件，而背包里实实在在躺着 64 件，
     * 余料永远退不掉。「用掉多少」现在由背包实际持有量与基线比出来（{@code WarehouseReturnQueue#pruneConsumed}），
     * 不需要这一层再算一个中间量。</p>
     *
     * @return 新的账本（不修改入参）
     */
    public static Map<String, Integer> add(Map<String, Integer> owed, Map<String, Integer> delivered) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (owed != null) {
            for (Map.Entry<String, Integer> entry : owed.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && entry.getValue() > 0) {
                    out.put(entry.getKey(), entry.getValue());
                }
            }
        }
        if (delivered != null) {
            for (Map.Entry<String, Integer> entry : delivered.entrySet()) {
                String itemId = entry.getKey();
                int count = entry.getValue() == null ? 0 : entry.getValue();
                if (itemId == null || count <= 0) {
                    continue;
                }
                out.merge(itemId, count, Integer::sum);
            }
        }
        return out;
    }

    /** 一份「物品 → 件数」表的总件数 */
    public static int total(Map<String, Integer> counts) {
        if (counts == null || counts.isEmpty()) {
            return 0;
        }
        int total = 0;
        for (Integer count : counts.values()) {
            if (count != null && count > 0) {
                total += count;
            }
        }
        return total;
    }

    /**
     * 一轮补料回执里「仓库到底给了多少件」（逐物品累加，只算到手的）。
     *
     * <p>🔴 <b>不是</b>「到手 − 缺口」。这一条被真机打过脸：自动填充曾经在回执到达前就关掉了容器，
     * 续填压根没发生，而「缺口以内的都算用掉了」这个前提随之破产 —— 账上只记下 23 件、
     * 背包里躺着 64 件，余料一件都退不掉。「用掉多少」改由背包持有量与基线比出来，
     * 这里只负责老实记「到手多少」。</p>
     *
     * @param report 一轮补料的结算表（{@code WarehouseFillPlan.Delivery} 列表）
     */
    public static Map<String, Integer> deliveredOf(List<WarehouseFillPlan.Delivery> report) {
        Map<String, Integer> out = new LinkedHashMap<>();
        if (report == null) {
            return out;
        }
        for (WarehouseFillPlan.Delivery delivery : report) {
            if (delivery == null || delivery.itemId() == null || delivery.itemId().isBlank()) {
                continue;
            }
            if (delivery.got() <= 0) {
                continue;
            }
            out.merge(delivery.itemId(), delivery.got(), Integer::sum);
        }
        return out;
    }

}
