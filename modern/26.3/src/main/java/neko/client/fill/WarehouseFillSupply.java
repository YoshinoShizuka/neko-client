package neko.client.fill;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import neko.client.WarehouseTakeNetwork;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * 把填充缺料变成一串「从虚空仓库取货」请求的收发管家。
 *
 * <h2>为什么要单独一层</h2>
 * <p>{@code printer_take} 一次只取一种物品，一次回执只回答一种物品。填充模板
 * 常常同时缺好几种，所以必须排队、逐个等回执，全部结束后再让 {@code QuickContainerCopy}
 * 重填一次模板。成功判据仍由 {@code WarehouseTakeModel} 给，这里不另立一套。</p>
 *
 * <h2>为什么要回传「每种到手多少」</h2>
 * <p>调用方拿到的缺口（{@code MissingDemand}）是<b>投影容器里要求多少</b>减去
 * 「容器内已有 + 背包已有」。这里回传的是<b>仓库这次真正补进背包多少</b>。
 * 两者一比就是「仓库够不够、还差多少」—— 这正是玩家真正想知道的那句话；
 * 只回 boolean 的话，填充失败时只能说一句「缺料」，看不出是仓库没有、
 * 仓库只有一部分、还是背包放不下。</p>
 */
public final class WarehouseFillSupply {
    private static WarehouseFillPlan plan;
    private static Consumer<List<WarehouseFillPlan.Delivery>> onDone;
    private static boolean active;

    private WarehouseFillSupply() { }

    public static boolean isActive() {
        return active;
    }

    /** 只要「有没有补到货」的旧入口（手持补货等场景不需要缺口数字） */
    public static boolean begin(Minecraft client, List<String> itemIds, Consumer<Boolean> done) {
        return start(client, new WarehouseFillPlan(itemIds),
                done == null ? null : report -> done.accept(anyDelivered(report)));
    }

    /**
     * 带缺口比对的入口。
     *
     * @param needByItem 物品 id → 投影容器还差多少（按插入顺序逐个请求，最多 8 种）
     * @param done       全部请求结算后回调：每种物品的「要多少 / 到手多少」
     */
    public static boolean begin(Minecraft client, Map<String, Integer> needByItem,
                                Consumer<List<WarehouseFillPlan.Delivery>> done) {
        return start(client, new WarehouseFillPlan(needByItem), done);
    }

    private static boolean start(Minecraft client, WarehouseFillPlan newPlan,
                                 Consumer<List<WarehouseFillPlan.Delivery>> done) {
        cancel();
        if (newPlan == null || newPlan.isDone()) {
            if (done != null) {
                done.accept(List.of());
            }
            return false;
        }
        plan = newPlan;
        onDone = done;
        active = true;
        pump(client);
        return true;
    }

    private static boolean anyDelivered(List<WarehouseFillPlan.Delivery> report) {
        if (report == null) {
            return false;
        }
        for (WarehouseFillPlan.Delivery delivery : report) {
            if (delivery.got() > 0) {
                return true;
            }
        }
        return false;
    }

    private static void pump(Minecraft client) {
        if (!active || plan == null) {
            return;
        }
        String id = plan.next();
        if (id == null) {
            complete();
            return;
        }
        Identifier key = Identifier.tryParse(id);
        ItemStack sample = key != null && BuiltInRegistries.ITEM.containsKey(key)
                ? new ItemStack(BuiltInRegistries.ITEM.getValue(key))
                : ItemStack.EMPTY;
        if (sample.isEmpty()) {
            plan.record(0);
            pump(client);
            return;
        }
        boolean sent = WarehouseTakeNetwork.beginForItemCount(client, sample, true, got -> {
            if (!active || plan == null) {
                return;
            }
            plan.record(got);
            pump(client);
        });
        if (!sent) {
            // 发包前就失败（通道不支持 / 已有请求在飞）不会走回调，这里兜底记一次
            if (active && plan != null) {
                plan.record(0);
                pump(client);
            }
        }
    }

    private static void complete() {
        List<WarehouseFillPlan.Delivery> report = plan == null ? List.of() : plan.deliveries();
        Consumer<List<WarehouseFillPlan.Delivery>> callback = onDone;
        cancel();
        if (callback != null) {
            callback.accept(report);
        }
    }

    /** 取消整条补料链（断线 / 关规则 / 玩家松手） */
    public static void cancel() {
        active = false;
        plan = null;
        onDone = null;
        WarehouseTakeNetwork.cancelFillRequest();
    }
}
