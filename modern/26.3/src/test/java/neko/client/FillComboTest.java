package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FillCombo} 的单测：<b>不启动游戏、不碰 malilib</b>。
 *
 * <p>咬的是「取满手势」那条判据的每一种错法。它最大的特点是<b>错了不会崩</b> ——
 * 只会「按下去什么都不发生」，玩家看不出是模组的问题、开发者也拿不到任何日志。
 * 0.4.3 就是这样发出去的：单测全绿、jar 断言全绿、真机一动不动。原因是那一版
 * 单测把「键盘状态」当成一个可以随便喂的 {@code IntPredicate}，于是<b>只测到了自己的
 * 逻辑，没测到「那个 API 在 26.3 上到底有没有用」</b>。现在键盘那半边收窄成
 * 三个布尔值（由点击事件自带的掩码给出），喂进来的就是调用方真正会拿到的东西。</p>
 *
 * <p>键码约定（malilib）：鼠标键 = {@code 原版按钮号 - 100}，所以 26.3 左键
 * {@code 1-100=-99}、其余版本左键 {@code 0-100=-100}；两个版本都要认。</p>
 */
class FillComboTest {

    /** 键码只用来判「是不是鼠标键」（负数），具体数值不影响判据 —— 这里只用两种版本的左键 */
    private static final int MOUSE_LEFT_263 = 1 - 100;
    private static final int MOUSE_LEFT_OLD = 0 - 100;

    /** 26.3 的点击按钮号（1 基）/ 26.2 及更早（0 基） */
    private static final int BTN_LEFT_263 = 1;
    private static final int BTN_LEFT_OLD = 0;
    private static final int BTN_RIGHT_263 = 3;

    /** 键盘键码只要有「非负」这个性质就够了，判据不认具体值 */
    private static final int ANY_KEY = 340;

    private static final List<String> CTRL_ALT_LEFT =
            List.of("LEFT_CONTROL", "LEFT_ALT", "MOUSE_LEFT");

    private static FillCombo.Combo ctrlAltLeft() {
        return FillCombo.parse(List.of(ANY_KEY, ANY_KEY + 1, MOUSE_LEFT_263), CTRL_ALT_LEFT);
    }

    // ------------------------------------------------------------ A 组：认键名

    @Test
    @DisplayName("A1 修饰键按名字后缀认：左右各一份、缩写也认，别的一律不认")
    void modifierByName() {
        assertAll(
                () -> assertEquals(FillCombo.Modifier.CONTROL, FillCombo.modifierOf("LEFT_CONTROL")),
                () -> assertEquals(FillCombo.Modifier.CONTROL, FillCombo.modifierOf("RIGHT_CONTROL")),
                () -> assertEquals(FillCombo.Modifier.CONTROL, FillCombo.modifierOf("RCONTROL")),
                () -> assertEquals(FillCombo.Modifier.SHIFT, FillCombo.modifierOf("LEFT_SHIFT")),
                () -> assertEquals(FillCombo.Modifier.SHIFT, FillCombo.modifierOf("RSHIFT")),
                () -> assertEquals(FillCombo.Modifier.ALT, FillCombo.modifierOf("LEFT_ALT")),
                () -> assertEquals(FillCombo.Modifier.ALT, FillCombo.modifierOf("RIGHT_ALT")),
                // 后缀匹配天然挡掉 SDL 那两个冷门键，不用特判
                () -> assertNull(FillCombo.modifierOf("KEY_ALTER_ASE"), "ALTER_ASE 不该被当成 Alt"),
                // 字母 / 鼠标键名 / null 都不是修饰键
                () -> assertNull(FillCombo.modifierOf("KEY_A")),
                () -> assertNull(FillCombo.modifierOf("MOUSE_LEFT")),
                () -> assertNull(FillCombo.modifierOf("BUTTON_1")),
                () -> assertNull(FillCombo.modifierOf(null)));
    }

    @Test
    @DisplayName("A2 显示串拆键名：空绑定给空表（别把空串当成一个叫「」的键）")
    void namesSplit() {
        assertAll(
                () -> assertEquals(List.of(), FillCombo.namesOf(null)),
                () -> assertEquals(List.of(), FillCombo.namesOf("")),
                () -> assertEquals(List.of(), FillCombo.namesOf("   ")),
                () -> assertEquals(List.of("MOUSE_LEFT"), FillCombo.namesOf("MOUSE_LEFT")),
                () -> assertEquals(CTRL_ALT_LEFT, FillCombo.namesOf("LEFT_CONTROL + LEFT_ALT + MOUSE_LEFT")),
                () -> assertEquals(3, FillCombo.namesOf("LEFT_CONTROL + LEFT_ALT + MOUSE_LEFT").size()));
    }

    // ------------------------------------------------------------ B 组：解析

    @Test
    @DisplayName("B1 默认组合键解析成「鼠标键 + Ctrl + Alt」")
    void parseDefault() {
        FillCombo.Combo combo = ctrlAltLeft();
        assertAll(
                () -> assertTrue(combo.usable(), () -> "默认组合键必须可用，实际：" + combo.problem()),
                () -> assertEquals(MOUSE_LEFT_263, combo.mouseCode()),
                () -> assertTrue(combo.control(), "Ctrl 要算进去"),
                () -> assertTrue(combo.alt(), "Alt 要算进去"),
                () -> assertFalse(combo.shift(), "没绑 Shift"));
    }

    @Test
    @DisplayName("B2 空绑定 ⇒ 不可用，且理由说得出口（不是静默 false）")
    void parseEmpty() {
        assertAll(
                () -> assertFalse(FillCombo.parse(List.of(), List.of()).usable()),
                () -> assertFalse(FillCombo.parse(null, List.of()).usable()),
                () -> assertTrue(FillCombo.parse(List.of(), List.of()).problem().contains("为空"),
                        "「清空 = 关掉手势」要在日志里说清楚"));
    }

    @Test
    @DisplayName("B3 没有鼠标键 / 有俩鼠标键 ⇒ 都不可用（取满得靠点击定位，且按不住两个）")
    void parseMouseCount() {
        FillCombo.Combo none = FillCombo.parse(
                List.of(ANY_KEY, ANY_KEY + 1), List.of("LEFT_CONTROL", "LEFT_ALT"));
        FillCombo.Combo two = FillCombo.parse(
                List.of(ANY_KEY, MOUSE_LEFT_263, MOUSE_LEFT_OLD),
                List.of("LEFT_CONTROL", "MOUSE_LEFT", "MOUSE_RIGHT"));
        assertAll(
                () -> assertFalse(none.usable()),
                () -> assertTrue(none.problem().contains("没有鼠标键")),
                () -> assertFalse(two.usable()),
                () -> assertTrue(two.problem().contains("2 个鼠标键")));
    }

    @Test
    @DisplayName("B4 绑了字母键 ⇒ 不可用，并把那个键名带进理由（原版看不见字母键的实时状态）")
    void parseNonModifier() {
        FillCombo.Combo combo = FillCombo.parse(
                List.of(ANY_KEY, MOUSE_LEFT_263), List.of("KEY_A", "MOUSE_LEFT"));
        assertAll(
                () -> assertFalse(combo.usable()),
                () -> assertTrue(combo.problem().contains("KEY_A"),
                        () -> "理由里必须带出到底是哪个键 —— 否则玩家只能盲猜，实际：" + combo.problem()));
    }

    @Test
    @DisplayName("B5 键名与键码数量对不上 ⇒ 不可用（宁可手势不生效，也不许张冠李戴）")
    void parseMismatch() {
        FillCombo.Combo combo = FillCombo.parse(List.of(ANY_KEY, MOUSE_LEFT_263), List.of("LEFT_CONTROL"));
        assertAll(
                () -> assertFalse(combo.usable()),
                () -> assertTrue(combo.problem().contains("对不上")),
                () -> assertFalse(FillCombo.parse(List.of(ANY_KEY), null).usable()));
    }

    // ------------------------------------------------------------ C 组：命中判定

    @Test
    @DisplayName("C1 默认组合键：26.3（按钮号 1 基）与旧版（0 基）都必须命中")
    void defaultComboMatchesOnBothEncodings() {
        assertAll(
                () -> assertTrue(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, false, true, true),
                        "26.3：Ctrl+Alt+左键 必须命中"),
                () -> assertTrue(FillCombo.matches(
                                FillCombo.parse(List.of(ANY_KEY, ANY_KEY + 1, MOUSE_LEFT_OLD), CTRL_ALT_LEFT),
                                BTN_LEFT_OLD, false, true, true),
                        "旧版：同一份源码必须同样命中"));
    }

    @Test
    @DisplayName("C2 少按一个修饰键 ⇒ 不算")
    void missingModifierFails() {
        assertAll(
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, false, true, false),
                        "Alt 没按却命中 ⇒ 单按 Ctrl 左键就会误触发"),
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, false, false, true),
                        "Ctrl 没按却命中 ⇒ 单按 Alt 左键就会误触发"),
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, false, false, false)));
    }

    @Test
    @DisplayName("C3 点错按钮 / 组合键不可用 / combo 为 null ⇒ 一律不算")
    void notMatchingCases() {
        FillCombo.Combo broken = FillCombo.parse(List.of(ANY_KEY, MOUSE_LEFT_263), List.of("KEY_A", "MOUSE_LEFT"));
        assertAll(
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_RIGHT_263, false, true, true),
                        "点右键不该被当成左键"),
                () -> assertFalse(FillCombo.matches(broken, BTN_LEFT_263, false, true, true),
                        "不可用的绑定不许命中（否则等于把写死的 Ctrl+Alt 又请回来）"),
                () -> assertFalse(FillCombo.matches(null, BTN_LEFT_263, false, true, true)));
    }

    @Test
    @DisplayName("C4 多按了没绑的修饰键 ⇒ 仍然命中（同 malilib 的 allowExtraKeys）")
    void extraModifierStillMatches() {
        assertTrue(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, true, true, true),
                "绑 Ctrl+Alt 的人按住 Shift 也该能取满 —— 多按不算错");
    }

    @Test
    @DisplayName("C5 只绑一个鼠标键 ⇒ 任何修饰键状态都命中（玩家想这么用就该这么用）")
    void bareMouseButtonWorks() {
        FillCombo.Combo bare = FillCombo.parse(List.of(MOUSE_LEFT_263), List.of("MOUSE_LEFT"));
        assertAll(
                () -> assertTrue(bare.usable()),
                () -> assertTrue(FillCombo.matches(bare, BTN_LEFT_263, false, false, false),
                        "不带修饰键的裸左键绑定必须生效，否则玩家清空修饰键后功能就「神秘失灵」"),
                () -> assertTrue(FillCombo.matches(bare, BTN_LEFT_263, true, true, true)));
    }
}
