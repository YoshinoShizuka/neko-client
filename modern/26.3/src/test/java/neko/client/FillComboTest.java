package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FillCombo} 的单测：<b>不启动游戏、不碰 malilib</b>。
 *
 * <p>咬的是「取满手势」那条判据的每一种错法。它最大的特点是<b>错了不会崩</b> ——
 * 只会「按下去什么都不发生」，玩家看不出是模组的问题、开发者也拿不到任何日志。
 * 所以只能靠这一层把每种组合都摆出来。</p>
 *
 * <p>键码约定（malilib）：{@code 原版按钮号 - 100} ⇒ 26.3 左键 {@code 1-100=-99}、
 * 其余版本左键 {@code 0-100=-100}；键盘键是非负数（GLFW：LEFT_CONTROL=340、LEFT_ALT=342）。
 * 这里两组都测：判据必须同时认这两种编码。</p>
 */
class FillComboTest {

    /** GLFW 键码，与 malilib 的 KeyCodes 一致 */
    private static final int LEFT_CONTROL = 340;
    private static final int LEFT_ALT = 342;

    /** 26.3 的取值（button() 1 基） */
    private static final int BTN_LEFT_263 = 1;
    private static final int BTN_RIGHT_263 = 3;
    /** 26.2 及更早的取值（button() 0 基） */
    private static final int BTN_LEFT_OLD = 0;
    private static final int BTN_RIGHT_OLD = 1;

    // ------------------------------------------------------------ A 组：空绑定 / 缺鼠标键

    @Test
    @DisplayName("A1 空绑定 ⇒ 恒 false（清空组合键 = 关掉手势，开关开着也不响应）")
    void emptyBindingNeverMatches() {
        assertAll(
                () -> assertFalse(FillCombo.matches(List.of(), BTN_LEFT_263, code -> true),
                        "空绑定不能让任何点击变成取满 —— 否则「清空」成了「全点都取满」"),
                () -> assertFalse(FillCombo.matches(List.of(), BTN_LEFT_OLD, code -> true)),
                () -> assertFalse(FillCombo.matches(null, BTN_LEFT_263, code -> true),
                        "null 也算没绑"));
    }

    @Test
    @DisplayName("A2 只绑了键盘键（没有鼠标键）⇒ 点哪儿都不算（没法表达「取满哪一格」）")
    void keyboardOnlyBindingNeverMatches() {
        List<Integer> keys = List.of(LEFT_CONTROL, LEFT_ALT);
        assertAll(
                () -> assertFalse(FillCombo.matches(keys, BTN_LEFT_263, code -> true)),
                () -> assertFalse(FillCombo.matches(keys, BTN_RIGHT_263, code -> true)));
    }

    // ------------------------------------------------------------ B 组：正常命中

    @Test
    @DisplayName("B1 默认组合键：两个版本编码下都命中（26.3 的 -99 / 旧版的 -100）")
    void defaultComboMatchesOnBothEncodings() {
        List<Integer> modern = List.of(LEFT_CONTROL, LEFT_ALT, BTN_LEFT_263 - 100);
        List<Integer> legacy = List.of(LEFT_CONTROL, LEFT_ALT, BTN_LEFT_OLD - 100);
        assertAll(
                () -> assertTrue(FillCombo.matches(modern, BTN_LEFT_263, code -> true),
                        "26.3：Ctrl+Alt+左键 必须命中"),
                () -> assertTrue(FillCombo.matches(legacy, BTN_LEFT_OLD, code -> true),
                        "旧版：同一份源码必须同样命中"));
    }

    @Test
    @DisplayName("B2 只绑一个鼠标键（不带修饰键）⇒ 点它就命中 —— 玩家想这么用就该这么用")
    void bareMouseButtonWorks() {
        assertAll(
                () -> assertTrue(FillCombo.matches(List.of(BTN_LEFT_263 - 100), BTN_LEFT_263, code -> false),
                        "isKeyDown 全 false 也不影响：唯一的键就是鼠标键本身"),
                () -> assertTrue(FillCombo.matches(List.of(BTN_LEFT_OLD - 100), BTN_LEFT_OLD, code -> false)));
    }

    // ------------------------------------------------------------ C 组：各种不该命中的局面

    @Test
    @DisplayName("C1 少按一个修饰键 ⇒ 不算（缺一个都不行）")
    void missingModifierFails() {
        List<Integer> keys = List.of(LEFT_CONTROL, LEFT_ALT, BTN_LEFT_263 - 100);
        // 只按着 Ctrl，Alt 没按
        Set<Integer> down = Set.of(LEFT_CONTROL, BTN_LEFT_263 - 100);
        assertFalse(FillCombo.matches(keys, BTN_LEFT_263, down::contains),
                "Alt 没按却命中 ⇒ 玩家单按 Ctrl 左键就会误触发");
    }

    @Test
    @DisplayName("C2 点了别的鼠标键 ⇒ 不算（右键不是绑定的那个键）")
    void otherMouseButtonFails() {
        List<Integer> keys = List.of(LEFT_CONTROL, LEFT_ALT, BTN_LEFT_263 - 100);
        assertAll(
                () -> assertFalse(FillCombo.matches(keys, BTN_RIGHT_263, code -> true),
                        "26.3：右键不该被当成左键"),
                () -> assertFalse(FillCombo.matches(
                        List.of(LEFT_CONTROL, LEFT_ALT, BTN_LEFT_OLD - 100), BTN_RIGHT_OLD, code -> true),
                        "旧版：右键不该被当成左键"));
    }

    @Test
    @DisplayName("C3 绑定里有两个鼠标键 ⇒ 恒不命中（一个指针按不住两个键，不能猜）")
    void twoMouseButtonsNeverMatch() {
        List<Integer> keys = List.of(LEFT_CONTROL, BTN_LEFT_263 - 100, BTN_RIGHT_263 - 100);
        assertAll(
                () -> assertFalse(FillCombo.matches(keys, BTN_LEFT_263, code -> true)),
                () -> assertFalse(FillCombo.matches(keys, BTN_RIGHT_263, code -> true)));
    }

    @Test
    @DisplayName("C4 isKeyDown 传 null（退化路径）⇒ 有修饰键时不命中，纯鼠标键仍可命中")
    void nullKeyDownDoesNotExplode() {
        assertAll(
                () -> assertFalse(FillCombo.matches(
                        List.of(LEFT_CONTROL, BTN_LEFT_263 - 100), BTN_LEFT_263, null),
                        "拿不到键盘状态时宁可判否（不误触发）"),
                () -> assertTrue(FillCombo.matches(List.of(BTN_LEFT_263 - 100), BTN_LEFT_263, null)));
    }
}
