package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** 材料「已有数量」合并的边界：负数、零、正常相加、int 饱和。 */
class VoidWarehouseMaterialCountsTest {

    @Test
    @DisplayName("A1 仓库为 0 / 负数时只保留夹紧后的背包数量")
    void zeroOrNegativeWarehouseKeepsInventory() {
        assertAll(
                () -> assertEquals(64, VoidWarehouseMaterialCounts.merge(64, 0L)),
                () -> assertEquals(64, VoidWarehouseMaterialCounts.merge(64, -1L)),
                () -> assertEquals(0, VoidWarehouseMaterialCounts.merge(-5, 0L)),
                () -> assertEquals(10, VoidWarehouseMaterialCounts.merge(-5, 10L)));
    }

    @Test
    @DisplayName("A2 正常相加：背包 + 仓库，不是覆盖")
    void addsBothSources() {
        assertAll(
                () -> assertEquals(1728, VoidWarehouseMaterialCounts.merge(64, 1664L)),
                () -> assertEquals(1, VoidWarehouseMaterialCounts.merge(0, 1L)));
    }

    @Test
    @DisplayName("A3 🔴 溢出饱和在 Integer.MAX_VALUE，绝不回绕成负数")
    void saturatesInsteadOfWrapping() {
        assertAll(
                () -> assertEquals(Integer.MAX_VALUE,
                        VoidWarehouseMaterialCounts.merge(Integer.MAX_VALUE, 1L)),
                () -> assertEquals(Integer.MAX_VALUE,
                        VoidWarehouseMaterialCounts.merge(1, Long.MAX_VALUE)),
                () -> assertEquals(Integer.MAX_VALUE,
                        VoidWarehouseMaterialCounts.merge(Integer.MAX_VALUE, Long.MAX_VALUE)));
    }

    @Test
    @DisplayName("A4 边界值：刚好到上限仍是上限，差 1 个则正常")
    void exactBoundary() {
        assertAll(
                () -> assertEquals(Integer.MAX_VALUE,
                        VoidWarehouseMaterialCounts.merge(0, Integer.MAX_VALUE)),
                () -> assertEquals(Integer.MAX_VALUE - 1,
                        VoidWarehouseMaterialCounts.merge(0, Integer.MAX_VALUE - 1L)),
                () -> assertEquals(Integer.MAX_VALUE,
                        VoidWarehouseMaterialCounts.merge(1, Integer.MAX_VALUE - 1L)));
    }
}
