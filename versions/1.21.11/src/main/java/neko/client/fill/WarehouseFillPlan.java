package neko.client.fill;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 投影填充缺料时「向虚空仓库逐个要货」的<b>纯判据</b>：不引用 Minecraft / malilib。
 *
 * <h2>为什么按物品逐个发</h2>
 * <p>{@code printer_take} 的候选清单语义是「按顺序取第一个有货的」，而不是
 * 「把清单里每样都取一份」。填充模板缺的是<b>多种不同物品</b>，一次塞进候选表
 * 只会拿到其中一种，其余仍然缺。所以这里把缺料拆成一串单物品请求，逐个等回执。</p>
 *
 * <h2>成功判据不在自己这里</h2>
 * <p>每个请求是否真的到货由 {@link neko.client.WarehouseTakeModel#delivered}
 * 判定（回执 OK 还得背包真的变多）；这里只负责排队与统计，避免两份成功语义。</p>
 *
 * <h2>为什么还要记「需求多少」</h2>
 * <p>0.5.5 起这里同时充当<b>「投影容器里要多少」与「仓库实际给了多少」的比对表</b>：
 * 需求来自容器 NBT（{@code MissingDemand#count}），实得来自 {@code printer_take}
 * 回执结算后的背包增量。两者一比就能说出「仓库只补到 20 个、还缺 44」——
 * 只记 boolean 的话只能知道「没补齐」，说不出缺多少，也说不出到底是缺料还是
 * 背包放不下。</p>
 */
public final class WarehouseFillPlan {
    /** 与 {@code PrinterTakePayload.MAX_CANDIDATES} 同量级，防止异常模板打出超长请求链 */
    public static final int MAX_REQUESTS = 8;

    /**
     * 一种物品的结算结果。
     *
     * @param itemId 物品注册表 id
     * @param need   投影容器还差多少（0 = 需求未知，这条不参与比对）
     * @param got    本次从仓库真正进背包的件数
     */
    public record Delivery(String itemId, int need, int got) {

        /** 仓库没补齐的部分 */
        public int shortage() {
            return Math.max(0, this.need - this.got);
        }

        /** 仓库一件都没给 */
        public boolean nothing() {
            return this.got <= 0;
        }
    }

    private final Deque<String> pending = new ArrayDeque<>();
    private final Map<String, Integer> needByItem = new LinkedHashMap<>();
    private final Map<String, Integer> gotByItem = new LinkedHashMap<>();
    /** 最近一次 {@link #next()} 取出的物品 id（{@link #record(boolean)} 用它归账） */
    private String currentItem;
    private int attempted;
    private int delivered;

    /** 只有物品清单、没有需求数量（手持补货等场景）：不参与缺口比对 */
    public WarehouseFillPlan(List<String> itemIds) {
        this(needByItem(itemIds));
        for (String id : new ArrayList<>(this.needByItem.keySet())) {
            this.needByItem.put(id, 0);
        }
    }

    /**
     * @param needByItem 物品 id → 还差多少（按插入顺序请求；只取前 {@link #MAX_REQUESTS} 种）
     */
    public WarehouseFillPlan(Map<String, Integer> needByItem) {
        if (needByItem == null) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (Map.Entry<String, Integer> entry : needByItem.entrySet()) {
            String id = entry.getKey();
            if (id == null || id.isBlank()) {
                continue;
            }
            if (unique.add(id)) {
                this.needByItem.put(id, Math.max(0, entry.getValue() == null ? 0 : entry.getValue()));
            }
            if (unique.size() >= MAX_REQUESTS) {
                break;
            }
        }
        this.pending.addAll(unique);
    }

    private static Map<String, Integer> needByItem(List<String> itemIds) {
        Map<String, Integer> map = new LinkedHashMap<>();
        if (itemIds != null) {
            for (String id : itemIds) {
                if (id != null && !id.isBlank()) {
                    map.put(id, 0);
                }
            }
        }
        return map;
    }

    /** 下一个要请求的物品 id；全部问完返回 {@code null} */
    public String next() {
        this.currentItem = this.pending.pollFirst();
        return this.currentItem;
    }

    /** 记一次回执结果（只有 boolean 的旧路径） */
    public void record(boolean reallyDelivered) {
        this.record(reallyDelivered ? 1 : 0);
    }

    /**
     * 记一次回执结果（含到手件数）。
     *
     * @param got 本次真正进背包的件数；0 = 没到货
     */
    public void record(int got) {
        this.gotByItem.merge(this.currentItem, Math.max(0, got), Integer::sum);
        this.attempted++;
        if (got > 0) {
            this.delivered++;
        }
    }

    public boolean isDone() {
        return this.pending.isEmpty();
    }

    public int attempted() {
        return this.attempted;
    }

    public int delivered() {
        return this.delivered;
    }

    /** 一个物品都没到货 ⇒ 没必要再重填模板，直接原样报缺料 */
    public boolean anyDelivered() {
        return this.delivered > 0;
    }

    /** 按请求顺序给出「要多少 / 到手多少」，供调用方拼缺口提示 */
    public List<Delivery> deliveries() {
        List<Delivery> out = new ArrayList<>(this.needByItem.size());
        for (Map.Entry<String, Integer> entry : this.needByItem.entrySet()) {
            String id = entry.getKey();
            out.add(new Delivery(id, entry.getValue(),
                    this.gotByItem.getOrDefault(id, 0)));
        }
        return List.copyOf(out);
    }
}
