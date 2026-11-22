package neko.client;

/**
 * 「快速丢弃」的纯判据层：<b>不 import Minecraft / malilib / Fabric</b>，单测直接跑。
 *
 * <h2>为什么这些判据要单独拆出来</h2>
 * <p>这条功能看起来只是「按个键」，真正难查的是<b>到底该丢哪一格</b>：
 * 鼠标坐标 → 槽位命中 → 是不是仓库内容格 → 能不能拿。四个判据里任何一个反了，
 * 表现都是「按了没反应」或「丢了不该丢的东西」（导航行的翻页按钮、玩家背包）。
 * 把它们做成纯函数，才能在单测里把 45 / 54 / 90 这几个边界钉死。</p>
 *
 * <h2>为什么内容格是 45</h2>
 * <p>虚空仓库是 6 行大箱子：前 5 行 45 格是仓库内容，第 6 行 9 格是导航按钮，
 * 再往下 36 格是玩家背包 —— 与服务端 {@code VoidWarehouseData} 的
 * {@code PAGE_SIZE = 45} / {@code NAV_START = 45} / {@code TOTAL_SLOTS = 54} 一一对应。
 * 客户端读不到服务端的常量（那是服务端类），所以这里必须写死同一组数字；
 * 单测同时校验「54 + 36 的菜单才认」，防止把普通箱子（27 格）误判成仓库。</p>
 */
public final class WarehouseDropModel {

    /** 仓库内容格：5 行 × 9 列 */
    public static final int CONTENT_SLOTS = 45;
    /** 虚拟菜单总格数（内容 45 + 导航 9） */
    public static final int MENU_SLOTS = 54;
    /** 玩家背包格数（3 行 + 快捷栏） */
    public static final int PLAYER_SLOTS = 36;
    /** 原版大箱子的槽位命中框边长 */
    public static final int SLOT_SIZE = 18;

    private WarehouseDropModel() {
    }

    /**
     * 这一格是不是「仓库内容格」。
     *
     * <p>不只看下标，还要看整个菜单的格数：虚空仓库必须是 54 + 36 = 90 格。
     * 普通大箱子只有 54 格（没有玩家背包？不，普通大箱子也是 90），但普通箱子是
     * 27 + 36 = 63 —— 用总格数当第一道闸，才能保证「在普通箱子里按同一个热键」
     * 不会误丢玩家的东西。</p>
     */
    public static boolean isWarehouseContentSlot(int slotIndex, int totalSlots) {
        return totalSlots == MENU_SLOTS + PLAYER_SLOTS
                && slotIndex >= 0
                && slotIndex < CONTENT_SLOTS;
    }

    /** 鼠标是不是落在这个格子的 18×18 命中框里（与原版 {@code Slot} 的命中一致）。 */
    public static boolean isMouseOver(int slotX, int slotY, int mouseX, int mouseY,
                                      int guiLeft, int guiTop) {
        int left = guiLeft + slotX;
        int top = guiTop + slotY;
        return mouseX >= left && mouseX < left + SLOT_SIZE
                && mouseY >= top && mouseY < top + SLOT_SIZE;
    }

    /**
     * 这一格允许丢吗：必须<b>有物品</b>、<b>能被拿走</b>、且<b>可见</b>。
     *
     * <p>三个条件缺一不可：空格（屏障占位）没有可丢的东西；{@code mayPickup=false}
     * 的格子（导航按钮、被锁定的格子）点了也不该有任何动作；不可见的格子
     * （{@code x/y < 0}）只是逻辑占位，鼠标不可能真的落在上面。</p>
     */
    public static boolean canDrop(boolean hasItem, boolean mayPickup, boolean visible) {
        return hasItem && mayPickup && visible;
    }

    /** 整格丢弃的 button 值（服务端 {@code dropFromWarehouse} 里 {@code default} 分支）。 */
    public static final int BUTTON_WHOLE_SLOT = 2;

    /** GLFW 的 Q 键码（自 1.0 起未变；本工程编译类路径上没有 GLFW，所以写成常量）。 */
    public static final int KEY_Q = 81;

    /**
     * 这一下按键是不是「快速丢弃整格」：<b>Q + Shift，且不带 Ctrl</b>。
     *
     * <p>为什么必须排除 Ctrl：26.3 原版的 Ctrl+Q 是「丢一组」（button 1），
     * 我们只在 Shift+Q 上接管；Ctrl+Shift+Q 这种混按既不该丢整格、也不该吞掉，
     * 交给原版按「丢一组」处理最不意外。</p>
     */
    public static boolean isDropKey(int key, boolean shiftDown, boolean controlDown) {
        return key == KEY_Q && shiftDown && !controlDown;
    }
}
