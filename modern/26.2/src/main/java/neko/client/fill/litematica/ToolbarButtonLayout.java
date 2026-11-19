package neko.client.fill.litematica;

import java.util.List;

/**
 * 「容器填充物合计」按钮在 Litematica 材料列表工具栏里的落位（纯逻辑，可单测）。
 *
 * <h2>为什么必须算，不能写死</h2>
 * <p>工具栏是 {@code GuiMaterialList.createButtons()} 一个一个横向累加出来的：
 * {@code x += createButton(x, y, type) + gap}。按钮数量与宽度都随语言和列表类型变 ——
 * 中文实测 7 个（刷新列表 / 隐藏已有材料 / HUD信息显示 / 清除忽略项 / 清空缓存 / 导出 /
 * 导出到文件），支持渲染层时多一个「列表类型」；窗口太窄时 Litematica 还会把后半排折到
 * 第二行。所以红框那个位置只能说成「该行最右一个按钮的右边缘 + 间隔」。</p>
 *
 * <h2>为什么用「第一个按钮的 y」当行号</h2>
 * <p>窄窗口折行时 {@code createButtons()} 会把 {@code y} 改成 {@code screenHeight - 22}
 * 再继续加；此时 {@code y = 24} 那一行只剩前四个按钮，贴在最右一个后面仍然是红框的
 * 位置（不会撞到折下去的那一排）。而 {@code createButtons()} 里第一个建的永远是
 * 「刷新列表」，它一定在首行，所以拿它的 y 当行号在两种布局下都对。</p>
 */
public final class ToolbarButtonLayout {

    /** 工具栏首行的 y（{@code GuiMaterialList.createButtons()} 里写死 24）。 */
    public static final int DEFAULT_Y = 24;

    /** 工具栏距窗口左边距（{@code createButtons()} 里写死 12）。 */
    public static final int DEFAULT_X = 12;

    /** 与上一个按钮之间留的空。 */
    public static final int GAP = 4;

    /** 右侧留白：右边是「倍率」标签 + 输入框 + 信息图标，别压上去。 */
    public static final int RIGHT_MARGIN = 8;

    private ToolbarButtonLayout() {
    }

    /** 一个按钮的矩形，只需要 x / y / 宽。 */
    public record Box(int x, int y, int width) {

        public int right() {
            return this.x + this.width;
        }
    }

    /** 工具栏行号：有按钮就用第一个（刷新列表）的 y，一个都没有就退回默认值。 */
    public static int rowY(List<Box> boxes) {
        if (boxes == null || boxes.isEmpty()) {
            return DEFAULT_Y;
        }
        return boxes.get(0).y();
    }

    /** 该行里最右的右边缘；一个都没有返回 0。 */
    public static int rightmost(List<Box> boxes, int rowY) {
        if (boxes == null) {
            return 0;
        }
        int right = 0;
        for (Box box : boxes) {
            if (box.y() == rowY) {
                right = Math.max(right, box.right());
            }
        }
        return right;
    }

    /**
     * 贴在 {@code rightmostRight} 之后，同时不许出界、不许压到左边距。
     *
     * <p>右边算下来不够就夹到 {@code screenWidth - buttonWidth - RIGHT_MARGIN}；
     * 连这个都小于左边距（窗口窄到离谱）就退回左边距 —— 宁可压到「倍率」也不画到屏外。</p>
     */
    public static int xAfter(int rightmostRight, int buttonWidth, int screenWidth) {
        if (rightmostRight <= 0) {
            return DEFAULT_X;
        }
        int limit = screenWidth - buttonWidth - RIGHT_MARGIN;
        int x = Math.min(rightmostRight + GAP, limit);
        return Math.max(DEFAULT_X, x);
    }
}
