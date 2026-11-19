package neko.client.fill;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import neko.client.WarehouseTakeNetwork;

import java.util.List;
import java.util.function.Consumer;

/**
 * 把填充缺料变成一串「从虚空仓库取货」请求的收发管家。
 *
 * <h2>为什么要单独一层</h2>
 * <p>{@code printer_take} 一次只取一种物品，一次回执只回答一种物品。填充模板
 * 常常同时缺好几种，所以必须排队、逐个等回执，全部结束后再让 {@code QuickContainerCopy}
 * 重填一次模板。成功判据仍由 {@code WarehouseTakeModel#delivered} 给，这里不另立一套。</p>
 */
public final class WarehouseFillSupply {
    private static WarehouseFillPlan plan;
    private static Consumer<Boolean> onDone;
    private static boolean active;

    private WarehouseFillSupply() { }

    public static boolean isActive() {
        return active;
    }

    /**
     * 开始一轮补料。
     *
     * @param itemIds 缺料物品的全名 id（去重、最多 8 种）
     * @param done    所有请求结算后回调：true = 至少一种真的进了背包
     */
    public static boolean begin(Minecraft client, List<String> itemIds, Consumer<Boolean> done) {
        cancel();
        if (itemIds == null || itemIds.isEmpty()) {
            if (done != null) {
                done.accept(false);
            }
            return false;
        }
        plan = new WarehouseFillPlan(itemIds);
        onDone = done;
        active = true;
        pump(client);
        return true;
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
            plan.record(false);
            pump(client);
            return;
        }
        boolean sent = WarehouseTakeNetwork.beginForItem(client, sample, true, delivered -> {
            if (!active || plan == null) {
                return;
            }
            plan.record(delivered);
            pump(client);
        });
        if (!sent) {
            // 发包前就失败（通道不支持 / 已有请求在飞）不会走回调，这里兜底记一次
            if (active && plan != null) {
                plan.record(false);
                pump(client);
            }
        }
    }

    private static void complete() {
        boolean any = plan != null && plan.anyDelivered();
        Consumer<Boolean> callback = onDone;
        cancel();
        if (callback != null) {
            callback.accept(any);
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
