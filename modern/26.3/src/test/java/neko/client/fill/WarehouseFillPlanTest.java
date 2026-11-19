package neko.client.fill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    @Test
    @DisplayName("D1 🔴 需求与实得比对：够 / 只给一部分 / 一件没给，三种都要说得出差多少")
    void comparesNeedsAgainstDelivered() {
        Map<String, Integer> need = new LinkedHashMap<>();
        need.put("minecraft:stone", 64);
        need.put("minecraft:dirt", 32);
        need.put("minecraft:sand", 16);
        WarehouseFillPlan counting = new WarehouseFillPlan(need);
        String stone = counting.next();
        counting.record(64);
        String dirt = counting.next();
        counting.record(20);
        String sand = counting.next();
        counting.record(0);
        assertAll(
                () -> assertEquals(3, counting.deliveries().size()),
                () -> assertEquals(0, counting.deliveries().get(0).shortage(), "石头补齐了"),
                () -> assertEquals(20, counting.deliveries().get(1).got()),
                () -> assertEquals(12, counting.deliveries().get(1).shortage(), "泥土仓库只给到 20"),
                () -> assertTrue(counting.deliveries().get(2).nothing(), "沙子仓库一件都没有"),
                () -> assertEquals(16, counting.deliveries().get(2).shortage()),
                () -> assertEquals(2, counting.delivered(), "到货的种类数"),
                () -> assertEquals(stone, counting.deliveries().get(0).itemId()),
                () -> assertEquals(dirt, counting.deliveries().get(1).itemId()),
                () -> assertEquals(sand, counting.deliveries().get(2).itemId()));
    }

    @Test
    @DisplayName("D2 只有 id 没有数量时（手持补货场景）不参与缺口比对")
    void withoutNeedsHasNoShortage() {
        WarehouseFillPlan plan = new WarehouseFillPlan(List.of("minecraft:stone"));
        plan.next();
        plan.record(10);
        assertAll(
                () -> assertEquals(0, plan.deliveries().get(0).need()),
                () -> assertEquals(0, plan.deliveries().get(0).shortage(), "需求未知时不许报缺口"),
                () -> assertTrue(plan.anyDelivered()));
    }

}
