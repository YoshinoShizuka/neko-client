package neko.client.fill.litematica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * 「缺失」列的两种渲染路径判据。
 *
 * <p>核心是一条等式：<b>不管谁在画，最后画出来的缺口都必须等于「需求 − 已有」</b>。
 * 真机上踩过的错法是「把『需求 − 已有』交给会再减一次的渲染者」，
 * 结果是仓库只补到一半的行少报一半缺口，而且界面看上去完全正常。</p>
 */
class ContainerGapTest {

    @Test
    void trueGapNeverGoesNegative() {
        assertEquals(70, ContainerGap.trueGap(100, 30));
        assertEquals(0, ContainerGap.trueGap(100, 100));
        assertEquals(0, ContainerGap.trueGap(100, 100_000_000));
        assertEquals(0, ContainerGap.trueGap(0, 0));
    }

    @Test
    void printerPathStillLandsOnTheSameGap() {
        int[][] rows = {
                {4220, 0},          // 仓库里没有：缺口就是全部
                {100, 30},          // 只补到一半（最容易算错的那种）
                {100, 60},
                {100, 100},         // 刚好够
                {35, 1_000_000},    // 仓库远远超过需求
                {0, 0},
        };
        for (int[] row : rows) {
            int total = row[0];
            int available = row[1];
            int drawn = ContainerGap.printerGap(
                    ContainerGap.missingFor(total, available, true), available);
            assertEquals(ContainerGap.trueGap(total, available), drawn,
                    "printer 渲染时画出来的缺口必须等于「需求 − 已有」：total=" + total
                            + " available=" + available);
        }
    }

    @Test
    void vanillaPathDrawsTheValueAsIs() {
        assertEquals(70, ContainerGap.missingFor(100, 30, false));
        assertEquals(0, ContainerGap.missingFor(100, 300, false));
    }

    @Test
    void feedingTheSelfComputedGapToPrinterWouldUnderstateIt() {
        // 这条钉的是「为什么必须有这个分支」：仓库补到 30 / 需要 100，
        // 真缺口 70；把 70 交给 printer 再减一次已有会变成 40。
        int selfComputed = ContainerGap.missingFor(100, 30, false);
        assertEquals(70, selfComputed);
        assertNotEquals(ContainerGap.trueGap(100, 30),
                ContainerGap.printerGap(selfComputed, 30),
                "交给 printer 的必须是 total（100），不能是已扣过已有的 70");
    }
}
