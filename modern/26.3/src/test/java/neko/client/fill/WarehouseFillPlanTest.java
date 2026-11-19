package neko.client.fill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 投影填充缺料补货的纯判据：去重限长、逐个问、成功取交集后的统计。
 *
 * <p>这里刻意不测网络：真机上的坑是「候选清单语义搞错 → 一次只取到一种物品」，
 * 排队逻辑写对了才能靠回执续填。</p>
 */
class WarehouseFillPlanTest {

    @Test
    @DisplayName("A1 去重并保留顺序；空白 id 不入队")
    void dedupes() {
        WarehouseFillPlan plan = new WarehouseFillPlan(Arrays.asList(
                "minecraft:stone", "minecraft:dirt", "minecraft:stone", " ", null));
        assertAll(
                () -> assertEquals("minecraft:stone", plan.next()),
                () -> assertEquals("minecraft:dirt", plan.next()),
                () -> assertNull(plan.next()),
                () -> assertTrue(plan.isDone()));
    }

    @Test
    @DisplayName("A2 请求数封顶 8（与 printer_take 的候选上限同量级）")
    void capsRequests() {
        String[] ids = new String[20];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = "minecraft:item_" + i;
        }
        WarehouseFillPlan plan = new WarehouseFillPlan(List.of(ids));
        int count = 0;
        while (plan.next() != null) {
            count++;
        }
        assertEquals(WarehouseFillPlan.MAX_REQUESTS, count);
    }

    @Test
    @DisplayName("A3 🔴 只有一种到货也算「有补料」——否则模板不会重填")
    void anyDelivered() {
        WarehouseFillPlan plan = new WarehouseFillPlan(List.of("a", "b"));
        plan.next();
        plan.record(false);
        assertFalse(plan.anyDelivered());
        plan.next();
        plan.record(true);
        assertAll(
                () -> assertTrue(plan.anyDelivered()),
                () -> assertEquals(2, plan.attempted()),
                () -> assertEquals(1, plan.delivered()),
                () -> assertTrue(plan.isDone()));
    }

    @Test
    @DisplayName("A4 空清单直接结束，不产生任何请求")
    void empty() {
        WarehouseFillPlan plan = new WarehouseFillPlan(List.of());
        assertAll(
                () -> assertTrue(plan.isDone()),
                () -> assertNull(plan.next()),
                () -> assertFalse(plan.anyDelivered()));
    }
}
