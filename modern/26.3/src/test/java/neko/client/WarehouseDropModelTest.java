package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「快速丢弃」纯判据单测：不启动游戏、不碰网络。
 *
 * <p>这条功能的所有故障都发生在「丢哪一格」这一步：把导航行当内容格会点出翻页 /
 * 切盒模式，把玩家背包当内容格会丢玩家自己的东西，把普通箱子当仓库会让同一个热键
 * 在别处生效。这里把 45 / 54 / 90 三个边界、18×18 命中框、以及「空 / 不可拿 / 不可见」
 * 三个前置条件全部钉死。</p>
 */
class WarehouseDropModelTest {

    // ------------------------------------------------------------ A 组：内容格边界

    @Test
    @DisplayName("A1 🔴 只有 90 格菜单的前 45 格算仓库内容格")
    void onlyFirst45OfFullMenu() {
        int total = WarehouseDropModel.MENU_SLOTS + WarehouseDropModel.PLAYER_SLOTS;
        assertAll(
                () -> assertTrue(WarehouseDropModel.isWarehouseContentSlot(0, total)),
                () -> assertTrue(WarehouseDropModel.isWarehouseContentSlot(44, total)),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(45, total),
                        "45 是导航行第一格（翻页/切模式），绝不能当库存丢"),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(53, total),
                        "53 是导航行最后一格"),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(54, total),
                        "54 起是玩家背包"),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(89, total)));
    }

    @Test
    @DisplayName("A2 🔴 普通箱子（27+36=63）与更小的菜单一律不认")
    void nonWarehouseMenusRejected() {
        assertAll(
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(0, 63), "普通箱子"),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(0, 45), "只有内容格，没有导航/背包"),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(0, 0)),
                () -> assertFalse(WarehouseDropModel.isWarehouseContentSlot(-1, 90)));
    }

    // ------------------------------------------------------------ B 组：悬停命中

    @Test
    @DisplayName("B1 18×18 命中框是左闭右开：右下边界不算，避免相邻两格同时命中")
    void hoverBoxIsHalfOpen() {
        int left = 100;
        int top = 50;
        assertAll(
                () -> assertTrue(WarehouseDropModel.isMouseOver(0, 0, left, top, left, top)),
                () -> assertTrue(WarehouseDropModel.isMouseOver(0, 0, left + 17, top + 17, left, top)),
                () -> assertFalse(WarehouseDropModel.isMouseOver(0, 0, left + 18, top + 10, left, top),
                        "右边界属于下一格"),
                () -> assertFalse(WarehouseDropModel.isMouseOver(0, 0, left - 1, top, left, top)));
    }

    @Test
    @DisplayName("B2 GUI 偏移要叠加进去：同一格在屏幕不同位置时命中跟着走")
    void guiOffsetApplied() {
        assertAll(
                () -> assertTrue(WarehouseDropModel.isMouseOver(9, 9, 209, 159, 200, 150)),
                () -> assertFalse(WarehouseDropModel.isMouseOver(9, 9, 209, 159, 100, 50)));
    }

    // ------------------------------------------------------------ C 组：可丢条件

    @Test
    @DisplayName("C1 有物品 / 可拿 / 可见，三个都成立才允许丢")
    void canDropNeedsAllThree() {
        assertAll(
                () -> assertTrue(WarehouseDropModel.canDrop(true, true, true)),
                () -> assertFalse(WarehouseDropModel.canDrop(false, true, true), "空格（屏障占位）不能丢"),
                () -> assertFalse(WarehouseDropModel.canDrop(true, false, true), "导航按钮 / 锁定格不能丢"),
                () -> assertFalse(WarehouseDropModel.canDrop(true, true, false), "不可见格不能丢"));
    }

    // ------------------------------------------------------------ D 组：button 语义

    @Test
    @DisplayName("D1 🔴 button 必须与 26.3 原版 THROW 的预测一致：0 = 1 个，1 = 整格")
    void buttonMatchesVanillaPrediction() {
        assertAll(
                () -> assertEquals(0, WarehouseDropModel.button(false)),
                () -> assertEquals(1, WarehouseDropModel.button(true)));
    }
}
