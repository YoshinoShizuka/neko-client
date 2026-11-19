package neko.client.fill.litematica;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 容器填充物合计的纯逻辑判据。
 *
 * <p>扫描本身要 Litematica + 注册表，测不了；但「同一个物品散在多个容器里要不要相加」
 * 「数量不合法时算不算一条」这两条一旦写错，界面上就是一张错表，而且看上去一切正常。</p>
 */
class QuickLitematicaContainerTotalsTest {

    @Test
    void addsTheSameItemAcrossContainers() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        QuickLitematicaContainerTotals.addCount(counts, "minecraft:iron_ingot", 64);
        QuickLitematicaContainerTotals.addCount(counts, "minecraft:iron_ingot", 12);
        QuickLitematicaContainerTotals.addCount(counts, "minecraft:stick", 8);

        assertEquals(76, counts.get("minecraft:iron_ingot"),
                "同一个物品出现在两个容器里必须是相加，不能取其中一个");
        assertEquals(8, counts.get("minecraft:stick"));
        assertEquals(2, counts.size());
    }

    @Test
    void ignoresEmptyIdsAndNonPositiveCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        QuickLitematicaContainerTotals.addCount(counts, null, 5);
        QuickLitematicaContainerTotals.addCount(counts, "", 5);
        QuickLitematicaContainerTotals.addCount(counts, "minecraft:stone", 0);
        QuickLitematicaContainerTotals.addCount(counts, "minecraft:stone", -3);

        assertTrue(counts.isEmpty(), "空 id / 0 / 负数都不该在表里占一行：" + counts);
    }

    @Test
    void sumsTotalItems() {
        QuickLitematicaContainerTotals.Scan empty = QuickLitematicaContainerTotals.Scan.EMPTY;
        assertTrue(empty.isEmpty());
        assertEquals(0, empty.totalItems());
    }
}
