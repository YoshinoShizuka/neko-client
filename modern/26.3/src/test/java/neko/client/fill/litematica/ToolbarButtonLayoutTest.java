package neko.client.fill.litematica;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 工具栏落位的纯逻辑判据。
 *
 * <p>这几条错了界面上不会报错，只会「按钮跑到别的地方」：算错行号会画到折下去的那一排上，
 * 算错左边界会盖住「导出到文件」，不夹右边会顶出屏幕看不见。所以逐条钉住。</p>
 */
class ToolbarButtonLayoutTest {

    private static ToolbarButtonLayout.Box box(int x, int width) {
        return new ToolbarButtonLayout.Box(x, ToolbarButtonLayout.DEFAULT_Y, width);
    }

    @Test
    void rowYFallsBackWhenThereIsNoButton() {
        assertEquals(ToolbarButtonLayout.DEFAULT_Y, ToolbarButtonLayout.rowY(null));
        assertEquals(ToolbarButtonLayout.DEFAULT_Y, ToolbarButtonLayout.rowY(List.of()));
    }

    @Test
    void rowYFollowsTheFirstButton() {
        // 窄窗口时 Litematica 会把后半排折到 screenHeight - 22；首行永远是「刷新列表」。
        List<ToolbarButtonLayout.Box> boxes = List.of(
                new ToolbarButtonLayout.Box(12, 24, 50),
                new ToolbarButtonLayout.Box(63, 24, 80),
                new ToolbarButtonLayout.Box(12, 338, 40));
        assertEquals(24, ToolbarButtonLayout.rowY(boxes));
    }

    @Test
    void rightmostOnlyCountsTheSameRow() {
        List<ToolbarButtonLayout.Box> boxes = List.of(
                box(12, 50),                              // 12..62
                box(63, 80),                              // 63..143
                new ToolbarButtonLayout.Box(12, 338, 900), // 折下去那排，不算
                box(144, 60));                            // 144..204
        assertEquals(204, ToolbarButtonLayout.rightmost(boxes, 24),
                "别的行的按钮再宽也不能算进这一行");
        assertEquals(0, ToolbarButtonLayout.rightmost(boxes, 99),
                "一行都没有就是 0（调用方据此退回左边距）");
        assertEquals(0, ToolbarButtonLayout.rightmost(null, 24));
    }

    @Test
    void xAfterSitsJustRightOfTheLastButton() {
        // 截图实测：中文 7 个按钮，最后一个「导出到文件」右边缘约 401（GUI 单位，640 宽）。
        assertEquals(401 + ToolbarButtonLayout.GAP,
                ToolbarButtonLayout.xAfter(401, 60, 640));
    }

    @Test
    void xAfterNeverOverflowsTheRightEdge() {
        int width = 60;
        int screen = 640;
        int x = ToolbarButtonLayout.xAfter(600, width, screen);
        assertEquals(screen - width - ToolbarButtonLayout.RIGHT_MARGIN, x,
                "贴到最右也要留出右侧空白（那里是「倍率」输入框）");
        assertTrue(x + width <= screen, "按钮右边缘不许超过窗口宽度");
    }

    @Test
    void xAfterFallsBackToTheLeftMargin() {
        assertEquals(ToolbarButtonLayout.DEFAULT_X,
                ToolbarButtonLayout.xAfter(0, 60, 640),
                "一个按钮都没有时退回左边距");
        assertEquals(ToolbarButtonLayout.DEFAULT_X,
                ToolbarButtonLayout.xAfter(1, 60, 640),
                "右边缘贴着左边距时，加完间隔也不能比左边距还靠左");
        assertEquals(ToolbarButtonLayout.DEFAULT_X,
                ToolbarButtonLayout.xAfter(200, 300, 320),
                "窗口窄到放不下时也不能算成负数");
    }
}
