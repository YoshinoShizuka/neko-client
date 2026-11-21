package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WarehouseReturnModel} 的单测：纯判据，不启动游戏、不碰背包。
 *
 * <p>咬的是「写歪了照样能编译、只在真机上把玩家自己的存货退走」的那几条：
 * 整格覆盖、两道夹子、LRU 之外的扫描顺序、销账不越扣。</p>
 */
class WarehouseReturnModelTest {

    private static Map<String, Integer> budget(Object... pairs) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], (Integer) pairs[i + 1]);
        }
        return map;
    }

    private static WarehouseReturnModel.Slot slot(int index, String itemId, int count) {
        return new WarehouseReturnModel.Slot(index, itemId, count);
    }

    @Test
    @DisplayName("A1 需求侧就是余料：仓库给了 64、填充只要 20 ⇒ 剩 44 全退")
    void expectedLeftIsHonoured() {
        assertAll(
                () -> assertEquals(44, WarehouseReturnModel.returnable(44, 0, 44)),
                () -> assertEquals(64, WarehouseReturnModel.returnable(64, 0, 64)),
                () -> assertEquals(20, WarehouseReturnModel.returnable(20, 7, 27)));
    }

    @Test
    @DisplayName("A2 🔴 手里没多出来 ⇒ 一件都不退（填充用光了 / 玩家自己挪走了）")
    void noGainMeansNothing() {
        assertAll(
                () -> assertEquals(0, WarehouseReturnModel.returnable(44, 0, 0), "账上有余料但背包空了"),
                () -> assertEquals(0, WarehouseReturnModel.returnable(44, 20, 20), "回到基线"),
                () -> assertEquals(0, WarehouseReturnModel.returnable(44, 20, 7), "比基线还少"));
    }

    @Test
    @DisplayName("A3 🔴 持有侧夹住需求侧：填充失败时不许按账面数退")
    void heldGainCapsExpected() {
        // 账上算出来该剩 64，可手里只比开局多 20（填充其实只用了 0 个…说明别处少了 44）
        assertEquals(20, WarehouseReturnModel.returnable(64, 0, 20));
        // 玩家开局手里就有 30，仓库又给 64，现在手里 50 ⇒ 只多 20
        assertEquals(20, WarehouseReturnModel.returnable(64, 30, 50));
    }

    @Test
    @DisplayName("A4 需求侧夹住持有侧：玩家自己又捡了同种物品也不许全退")
    void expectedCapsHeld() {
        // 仓库给的 44 还在，玩家又挖了 56 ⇒ 手里多出 100，但只该退仓库那 44
        assertEquals(44, WarehouseReturnModel.returnable(44, 0, 100));
    }

    @Test
    @DisplayName("A5 没有余料 / 非法输入一律 0")
    void zeroAndGarbage() {
        assertAll(
                () -> assertEquals(0, WarehouseReturnModel.returnable(0, 0, 64)),
                () -> assertEquals(0, WarehouseReturnModel.returnable(-5, 0, 64)),
                () -> assertEquals(0, WarehouseReturnModel.returnable(44, -5, 0), "负基线当 0"),
                () -> assertEquals(44, WarehouseReturnModel.returnable(44, -5, 44)));
    }

    @Test
    @DisplayName("B1 扫描顺序：先主背包 35→9，再快捷栏 8→0，一格不漏一格不重")
    void scanOrderCoversEverything() {
        List<Integer> order = WarehouseReturnModel.scanOrder(36, 9);
        assertEquals(36, order.size());
        assertEquals(35, order.get(0), "主背包最后一格先扫");
        assertEquals(9, order.get(26), "主背包扫到第 9 格");
        assertEquals(8, order.get(27), "然后才是快捷栏");
        assertEquals(0, order.get(35), "快捷栏最后一格");
        assertAll(
                () -> assertEquals(36, Set.copyOf(order).size(), "不许重复"),
                () -> assertTrue(order.containsAll(java.util.stream.IntStream.range(0, 36)
                        .boxed().toList()), "0..35 要全覆盖"));
    }

    @Test
    @DisplayName("B2 扫描顺序的边界：小背包 / 快捷栏比背包还大 / 空背包")
    void scanOrderEdgeCases() {
        assertEquals(List.of(9, 8, 7, 6, 5, 4, 3, 2, 1, 0),
                WarehouseReturnModel.scanOrder(10, 4), "10 格、快捷栏 4 格");
        assertEquals(5, WarehouseReturnModel.scanOrder(5, 99).size(), "快捷栏夹到背包大小");
        assertEquals(List.of(4, 3, 2, 1, 0), WarehouseReturnModel.scanOrder(5, 99));
        assertTrue(WarehouseReturnModel.scanOrder(0, 9).isEmpty());
        assertTrue(WarehouseReturnModel.scanOrder(-3, 9).isEmpty());
        assertEquals(List.of(5, 4, 3, 2, 1, 0), WarehouseReturnModel.scanOrder(6, 0), "没有快捷栏");
    }

    @Test
    @DisplayName("C1 账本覆盖整格才上交")
    void planRequiresFullCoverage() {
        List<WarehouseReturnModel.Slot> picked = WarehouseReturnModel.plan(
                budget("minecraft:dirt", 64),
                List.of(slot(30, "minecraft:dirt", 64)),
                Set.of(), 36);
        assertEquals(1, picked.size());
        assertEquals(30, picked.get(0).index());
    }

    @Test
    @DisplayName("C2 🔴 账本只差 1 个也不许上交（服务端按整格收，放行就是把玩家的存货退走）")
    void planRejectsPartialCoverage() {
        assertTrue(WarehouseReturnModel.plan(
                        budget("minecraft:dirt", 63),
                        List.of(slot(30, "minecraft:dirt", 64)),
                        Set.of(), 36)
                .isEmpty(), "账本 63 < 整格 64");
    }

    @Test
    @DisplayName("C3 预算按格递减：账本 128 容许两格 64，账本 64 只容许一格")
    void planBudgetsPerItem() {
        List<WarehouseReturnModel.Slot> two = WarehouseReturnModel.plan(
                budget("minecraft:dirt", 128),
                List.of(slot(35, "minecraft:dirt", 64), slot(34, "minecraft:dirt", 64)),
                Set.of(), 36);
        assertEquals(2, two.size());

        List<WarehouseReturnModel.Slot> one = WarehouseReturnModel.plan(
                budget("minecraft:dirt", 64),
                List.of(slot(35, "minecraft:dirt", 64), slot(34, "minecraft:dirt", 64)),
                Set.of(), 36);
        assertEquals(1, one.size());
        assertEquals(35, one.get(0).index(), "按传入顺序挑，先扫到的先上");
    }

    @Test
    @DisplayName("C4 不在账本里的物品 / 被 skip 的槽位 / 无效格子一律跳过")
    void planSkipsForeignSlots() {
        List<WarehouseReturnModel.Slot> picked = WarehouseReturnModel.plan(
                budget("minecraft:dirt", 128),
                List.of(
                        slot(35, "minecraft:dirt", 64),
                        slot(34, "minecraft:stone", 64),      // 不在账上
                        slot(33, "minecraft:dirt", 64),       // 在账上但被 skip
                        slot(32, "minecraft:dirt", 0),        // 件数 0
                        slot(-1, "minecraft:dirt", 64),       // 非法下标
                        slot(31, "", 64),                     // 空 id
                        slot(30, "minecraft:dirt", 32)),
                Set.of(33), 36);
        assertEquals(2, picked.size(), "只剩 35 与 30");
        assertEquals(35, picked.get(0).index());
        assertEquals(30, picked.get(1).index());
    }

    @Test
    @DisplayName("C5 maxSlots 限流（服务端一次最多收 36 格）")
    void planHonoursMaxSlots() {
        List<WarehouseReturnModel.Slot> slots = new java.util.ArrayList<>();
        for (int i = 0; i < 10; i++) {
            slots.add(slot(30 - i, "minecraft:dirt", 1));
        }
        assertEquals(3, WarehouseReturnModel.plan(budget("minecraft:dirt", 64), slots, Set.of(), 3).size());
        assertEquals(10, WarehouseReturnModel.plan(budget("minecraft:dirt", 64), slots, Set.of(), 36).size());
        assertTrue(WarehouseReturnModel.plan(budget("minecraft:dirt", 64), slots, Set.of(), 0).isEmpty());
    }

    @Test
    @DisplayName("C6 空账本 / 空背包 / null 一律空清单")
    void planEmptyInputs() {
        assertAll(
                () -> assertTrue(WarehouseReturnModel.plan(Map.of(),
                        List.of(slot(0, "minecraft:dirt", 1)), Set.of(), 36).isEmpty()),
                () -> assertTrue(WarehouseReturnModel.plan(null,
                        List.of(slot(0, "minecraft:dirt", 1)), Set.of(), 36).isEmpty()),
                () -> assertTrue(WarehouseReturnModel.plan(budget("minecraft:dirt", 64),
                        List.of(), Set.of(), 36).isEmpty()),
                () -> assertTrue(WarehouseReturnModel.plan(budget("minecraft:dirt", 64),
                        null, Set.of(), 36).isEmpty()),
                () -> assertTrue(WarehouseReturnModel.plan(budget("minecraft:dirt", 0),
                        List.of(slot(0, "minecraft:dirt", 1)), Set.of(), 36).isEmpty()));
    }

    @Test
    @DisplayName("D1 销账按批次顺序扣，扣到 0 就移出账本")
    void deductWorks() {
        Map<String, Integer> owed = budget("minecraft:dirt", 64, "minecraft:stone", 32);
        Map<String, Integer> left = WarehouseReturnModel.deduct(owed, budget("minecraft:dirt", 64), 64);
        assertEquals(Map.of("minecraft:stone", 32), left);
    }

    @Test
    @DisplayName("D2 服务端实收少于批次 ⇒ 只扣它说的那些，剩下的还留在账上")
    void deductPartial() {
        Map<String, Integer> owed = budget("minecraft:dirt", 64);
        assertEquals(Map.of("minecraft:dirt", 44),
                WarehouseReturnModel.deduct(owed, budget("minecraft:dirt", 64), 20));
    }

    @Test
    @DisplayName("D3 🔴 一格都没退成 ⇒ 账本一件都不许销")
    void deductZeroKeepsLedger() {
        Map<String, Integer> owed = budget("minecraft:dirt", 64);
        assertEquals(owed, WarehouseReturnModel.deduct(owed, budget("minecraft:dirt", 64), 0));
        assertEquals(owed, WarehouseReturnModel.deduct(owed, budget("minecraft:dirt", 64), -3));
    }

    @Test
    @DisplayName("D4 批次里账本没有的物品跳过，不占扣减额度")
    void deductIgnoresForeignItems() {
        Map<String, Integer> owed = budget("minecraft:stone", 32);
        assertEquals(Map.of("minecraft:stone", 12),
                WarehouseReturnModel.deduct(owed, budget("minecraft:dirt", 64, "minecraft:stone", 20), 20));
    }

    @Test
    @DisplayName("D5 total 汇总：null / 空 / 混入非正数")
    void totalSums() {
        assertAll(
                () -> assertEquals(0, WarehouseReturnModel.total(null)),
                () -> assertEquals(0, WarehouseReturnModel.total(Map.of())),
                () -> assertEquals(96, WarehouseReturnModel.total(budget("a", 64, "b", 32))),
                () -> assertEquals(64, WarehouseReturnModel.total(budget("a", 64, "b", 0, "c", -7))));
    }

    @Test
    @DisplayName("E1 整条链路：仓库给 64、缺口 44 ⇒ 填充从那一格用掉 44、剩 20 ⇒ 整格覆盖，可以上交")
    void endToEnd() {
        // 开局手里 10 个（玩家自己的），仓库给 64、缺口 44 ⇒ 余料 20
        int expectedLeft = 64 - 44;
        int budgetForDirt = WarehouseReturnModel.returnable(expectedLeft, 10, 74);
        assertEquals(20, budgetForDirt);

        // 填充从仓库那一格用掉 44：那格变成 20，玩家自己的 10 格原地不动
        List<WarehouseReturnModel.Slot> picked = WarehouseReturnModel.plan(
                Map.of("minecraft:dirt", budgetForDirt),
                List.of(slot(35, "minecraft:dirt", 20), slot(34, "minecraft:dirt", 10)),
                Set.of(), 36);
        assertEquals(1, picked.size(), "只交还仓库那格的 20");
        assertEquals(20, picked.get(0).count());
        assertFalse(picked.isEmpty());

        // 反过来（仓库那格没被消耗、账上却只有 20）⇒ 一格都不许动，宁可少退
        assertTrue(WarehouseReturnModel.plan(
                Map.of("minecraft:dirt", budgetForDirt),
                List.of(slot(35, "minecraft:dirt", 64)),
                Set.of(), 36).isEmpty());
    }
}
