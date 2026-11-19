package neko.client.fill;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;

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
 */
public final class WarehouseFillPlan {
    /** 与 {@code PrinterTakePayload.MAX_CANDIDATES} 同量级，防止异常模板打出超长请求链 */
    public static final int MAX_REQUESTS = 8;

    private final Deque<String> pending = new ArrayDeque<>();
    private int attempted;
    private int delivered;

    public WarehouseFillPlan(List<String> itemIds) {
        if (itemIds == null) {
            return;
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String id : itemIds) {
            if (id != null && !id.isBlank()) {
                unique.add(id);
            }
            if (unique.size() >= MAX_REQUESTS) {
                break;
            }
        }
        this.pending.addAll(unique);
    }

    /** 下一个要请求的物品 id；全部问完返回 {@code null} */
    public String next() {
        return this.pending.pollFirst();
    }

    /** 记一次回执结果 */
    public void record(boolean reallyDelivered) {
        this.attempted++;
        if (reallyDelivered) {
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
}
