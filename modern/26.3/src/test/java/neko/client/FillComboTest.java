package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

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
 * <p><b>0.4.5 又交了一次学费</b>：那一版的 D 组单测把「绑左键、点右键也要命中」当成
 * 正确行为写死，于是单测 / jar 断言 / 真机取货全绿，玩家却报「实际生效的是右键」。
 * <b>功能可用性与语义正确性必须分开验</b>。D 组现已整组反过来：真机样本
 * 「绑 {@code -99} + 按住 Ctrl+Alt + {@code 按钮=3(-97)}」必须 <b>false</b>。</p>
 *
 * <p><b>这一轮又把 B3 / B4 反过来一次</b>（这次是真的改需求，不是放宽）：
 * 玩家把组合键改成「单个按键」之后取满一次都不生效，因为旧口径要求「必须有鼠标键」、
 * 且「非修饰键一律判否」。现在「没绑鼠标键」= 点任意键都算、「绑了键盘键」= 那些键要按着
 * （账本由 {@code FillKeyState} 提供）。<b>断言一条都没删</b>：B3 保留「俩鼠标键判否」，
 * B4 从「判否」改成「可用且键名进表」，D5 的「不可用样本」换成真正不可用的那种。</p>
 *
 * <p>键码约定（malilib）：鼠标键 = {@code 原版按钮号 - 100}，所以 26.3 左键
 * {@code 1-100=-99}、右键 {@code 3-100=-97}；其余版本左键 {@code 0-100=-100}、
 * 右键 {@code 1-100=-99}。配置里存的是键名，由各版本 malilib 自己解析，
 * 所以精确匹配跨版本自洽。</p>
 */
class FillComboTest {

    /** 26.3 的鼠标键码（javap 真机 malilib 0.30.2 实证）：左 -99 / 中 -98 / 右 -97 */
    private static final int MOUSE_LEFT_263 = 1 - 100;
    private static final int MOUSE_MIDDLE_263 = 2 - 100;
    private static final int MOUSE_RIGHT_263 = 3 - 100;
    /** 0 基版本（26.2 / 26.1.x / 1.21.11）：左 -100 / 右 -99 */
    private static final int MOUSE_LEFT_OLD = 0 - 100;
    private static final int MOUSE_RIGHT_OLD = 1 - 100;

    /** 26.3 的点击按钮号（1 基）/ 26.2 及更早（0 基） */
    private static final int BTN_LEFT_263 = 1;
    private static final int BTN_MIDDLE_263 = 2;
    private static final int BTN_RIGHT_263 = 3;
    private static final int BTN_LEFT_OLD = 0;
    private static final int BTN_RIGHT_OLD = 1;

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
    @DisplayName("B3 没有鼠标键 ⇒ 可用（单键取满）；俩鼠标键 ⇒ 仍判否（一个指针按不住两个）")
    void parseMouseCount() {
        FillCombo.Combo none = FillCombo.parse(
                List.of(ANY_KEY, ANY_KEY + 1), List.of("LEFT_CONTROL", "LEFT_ALT"));
        FillCombo.Combo single = FillCombo.parse(List.of(ANY_KEY), List.of("KEY_R"));
        FillCombo.Combo two = FillCombo.parse(
                List.of(ANY_KEY, MOUSE_LEFT_263, MOUSE_LEFT_OLD),
                List.of("LEFT_CONTROL", "MOUSE_LEFT", "MOUSE_RIGHT"));
        assertAll(
                () -> assertTrue(none.usable(), () -> "只绑修饰键也要能用，实际：" + none.problem()),
                () -> assertFalse(none.hasMouse(), "没绑鼠标键 ⇒ 点哪个键都算"),
                () -> assertTrue(single.usable(), () -> "单个键盘键必须可用，实际：" + single.problem()),
                () -> assertFalse(single.hasMouse()),
                () -> assertEquals(1, single.keys().size(), "那个键要进「必须按着」的表"),
                () -> assertFalse(two.usable()),
                () -> assertTrue(two.problem().contains("2 个鼠标键")));
    }

    @Test
    @DisplayName("B4 绑了字母键 ⇒ 可用，且那个键进「必须按着」的表（不再判否）")
    void parseNonModifier() {
        FillCombo.Combo combo = FillCombo.parse(
                List.of(ANY_KEY, MOUSE_LEFT_263), List.of("KEY_A", "MOUSE_LEFT"));
        assertAll(
                () -> assertTrue(combo.usable(), () -> "字母键 + 鼠标键必须可用，实际：" + combo.problem()),
                () -> assertTrue(combo.hasMouse()),
                () -> assertEquals(MOUSE_LEFT_263, combo.mouseCode()),
                () -> assertEquals(1, combo.keys().size()),
                () -> assertEquals("KEY_A", combo.keys().get(0).name(),
                        "键名要留着 —— 码值对不上时靠它兜底"),
                () -> assertTrue(combo.describeKeys().contains("KEY_A"), "日志要能说出是哪个键"),
                () -> assertTrue(combo.needsKeyboard()));
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

    // ------------------------------------------------------------ D 组：严格匹配（0.4.6）

    private static final List<String> CTRL_ALT_RIGHT =
            List.of("LEFT_CONTROL", "LEFT_ALT", "MOUSE_RIGHT");

    private static FillCombo.Combo ctrlAltRight() {
        return FillCombo.parse(List.of(ANY_KEY, ANY_KEY + 1, MOUSE_RIGHT_263), CTRL_ALT_RIGHT);
    }

    @Test
    @DisplayName("D1 🔴 真机 0.4.5 回归：绑左键 -99、Ctrl+Alt+右键(3/-97) 必须不命中")
    void boundLeftNeverMatchesRightClick() {
        assertAll(
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_RIGHT_263, false, true, true),
                        "0.4.5 就是靠 ±2 容错把这一击放行的 ⇒ 玩家报『实际生效的是 Ctrl+Alt+右键』"),
                () -> assertTrue(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_263, false, true, true),
                        "同一份绑定下唯一该命中的是左键"),
                () -> assertEquals(-99, MOUSE_LEFT_263, "26.3 MOUSE_LEFT"),
                () -> assertEquals(-98, MOUSE_MIDDLE_263, "26.3 MOUSE_MIDDLE"),
                () -> assertEquals(-97, MOUSE_RIGHT_263, "26.3 MOUSE_RIGHT = BUTTON_2"));
    }

    @Test
    @DisplayName("D2 想用右键就显式绑右键：绑 -97 时右键命中，左键 / 中键都不命中")
    void explicitRightBindingWorks() {
        FillCombo.Combo right = ctrlAltRight();
        assertAll(
                () -> assertTrue(right.usable(), () -> "显式绑右键必须可用，实际：" + right.problem()),
                () -> assertEquals(MOUSE_RIGHT_263, right.mouseCode()),
                () -> assertTrue(FillCombo.matches(right, BTN_RIGHT_263, false, true, true),
                        "显式绑 BUTTON_2 / MOUSE_RIGHT 后，Ctrl+Alt+右键就该取满"),
                () -> assertFalse(FillCombo.matches(right, BTN_LEFT_263, false, true, true),
                        "显式绑右键后左键不该再触发"),
                () -> assertFalse(FillCombo.matches(right, BTN_MIDDLE_263, false, true, true),
                        "中键也不该触发"));
    }

    @Test
    @DisplayName("D3 0 基 / 1 基版本差不许靠容错互串（配置存键名，码值由各版本 malilib 解析）")
    void versionOffsetDoesNotCrossMatch() {
        FillCombo.Combo oldLeft = FillCombo.parse(
                List.of(ANY_KEY, ANY_KEY + 1, MOUSE_LEFT_OLD), CTRL_ALT_LEFT);
        FillCombo.Combo oldRight = FillCombo.parse(
                List.of(ANY_KEY, ANY_KEY + 1, MOUSE_RIGHT_OLD), CTRL_ALT_RIGHT);
        assertAll(
                () -> assertTrue(FillCombo.matches(oldLeft, BTN_LEFT_OLD, false, true, true),
                        "0 基版本：BUTTON_1(-100) + 按钮 0 必须命中"),
                () -> assertTrue(FillCombo.matches(oldRight, BTN_RIGHT_OLD, false, true, true),
                        "0 基版本：BUTTON_2(-99) + 按钮 1 必须命中"),
                () -> assertFalse(FillCombo.matches(oldLeft, BTN_LEFT_263, false, true, true),
                        "旧版绑 -100 却点出 -99 ⇒ 必须判否（版本差由键名解析消掉，不靠放宽）"),
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_LEFT_OLD, false, true, true),
                        "26.3 绑 -99 却点出 -100 ⇒ 必须判否"));
    }

    @Test
    @DisplayName("D4 裸鼠标键绑定同样严格：只认那一个按钮")
    void bareMouseBindingIsStrict() {
        FillCombo.Combo bare = FillCombo.parse(List.of(MOUSE_LEFT_263), List.of("MOUSE_LEFT"));
        assertAll(
                () -> assertTrue(bare.usable()),
                () -> assertTrue(FillCombo.matches(bare, BTN_LEFT_263, false, false, false)),
                () -> assertFalse(FillCombo.matches(bare, BTN_RIGHT_263, true, true, true),
                        "只绑左键时，按住全部修饰键点右键也不许取满"));
    }

    @Test
    @DisplayName("D5 侧键 / 中键 / 不可用 / null 组合键一律不命中")
    void unusableAndSideButtonsNeverMatch() {
        // 🔴 这一轮：KEY_A + 鼠标键现在是<b>可用</b>的组合，不能再当「不可用样本」；
        //    换成真正不可用的那一种（绑了俩鼠标键）。
        FillCombo.Combo broken = FillCombo.parse(
                List.of(MOUSE_LEFT_263, MOUSE_RIGHT_263), List.of("MOUSE_LEFT", "MOUSE_RIGHT"));
        assertAll(
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), BTN_MIDDLE_263, false, true, true),
                        "中键(2→-98) 不许命中"),
                () -> assertFalse(FillCombo.matches(ctrlAltLeft(), 8, false, true, true),
                        "侧键(8→-92) 不许命中"),
                () -> assertFalse(FillCombo.matches(broken, BTN_LEFT_263, false, true, true)),
                () -> assertFalse(FillCombo.matches(null, BTN_LEFT_263, false, true, true)));
    }

    // ------------------------------------------------------------ E 组：单键取满

    /** 键盘键码：判据只认「非负」这个性质，具体值不重要 */
    private static final int KEY_R_CODE = 340;
    private static final int KEY_LEFT_ALT_CODE = 341;

    private static FillCombo.Combo singleKey() {
        return FillCombo.parse(List.of(KEY_R_CODE), List.of("KEY_R"));
    }

    private static FillCombo.Combo keyPlusLeft() {
        return FillCombo.parse(List.of(KEY_R_CODE, MOUSE_LEFT_263), List.of("KEY_R", "MOUSE_LEFT"));
    }

    @Test
    @DisplayName("E1 单键取满：按着那个键点任意鼠标键都算；没按着就一个都不算")
    void singleKeyMatchesAnyButtonWhenHeld() {
        Set<Integer> held = Set.of(KEY_R_CODE);
        assertAll(
                () -> assertTrue(FillCombo.matches(singleKey(), BTN_LEFT_263, false, false, false,
                                held, Set.of()), "左键"),
                () -> assertTrue(FillCombo.matches(singleKey(), BTN_RIGHT_263, false, false, false,
                                held, Set.of()), "右键 —— 玩家没绑鼠标键，就没有「该点哪个」"),
                () -> assertTrue(FillCombo.matches(singleKey(), BTN_MIDDLE_263, false, false, false,
                                held, Set.of()), "中键"),
                () -> assertFalse(FillCombo.matches(singleKey(), BTN_LEFT_263, false, false, false,
                                Set.of(), Set.of()), "没按着绑定键 ⇒ 不许取满（否则点一下就取满）"),
                () -> assertFalse(FillCombo.matches(singleKey(), BTN_LEFT_263, false, false, false),
                        "不带账本的旧签名 = 一个键都没按着 ⇒ 也是 false"));
    }

    @Test
    @DisplayName("E2 键盘键 + 鼠标键：鼠标键仍然严格，键盘键必须按着")
    void keyPlusMouseIsStrict() {
        Set<Integer> held = Set.of(KEY_R_CODE);
        assertAll(
                () -> assertTrue(FillCombo.matches(keyPlusLeft(), BTN_LEFT_263, false, false, false,
                                held, Set.of()), "按着 R 点左键 ⇒ 命中"),
                () -> assertFalse(FillCombo.matches(keyPlusLeft(), BTN_RIGHT_263, false, false, false,
                                held, Set.of()), "按着 R 点右键 ⇒ 不许命中（绑了左键就是左键）"),
                () -> assertFalse(FillCombo.matches(keyPlusLeft(), BTN_LEFT_263, false, false, false,
                        Set.of(), Set.of()), "没按 R ⇒ 不许命中"));
    }

    @Test
    @DisplayName("E3 码值对不上时按名字兜底（两边编码万一分叉，功能也不会静默失效）")
    void nameFallbackSavesTheDay() {
        Set<String> byName = Set.of(FillCombo.normalizeName("key.keyboard.r"));
        Set<Integer> wrongCode = Set.of(KEY_R_CODE + 999);
        assertAll(
                () -> assertTrue(FillCombo.keysHeld(singleKey(), wrongCode, byName),
                        "码值不同、名字相同 ⇒ 算按着"),
                () -> assertFalse(FillCombo.keysHeld(singleKey(), wrongCode, Set.of()),
                        "两边都对不上 ⇒ 不算"),
                () -> assertEquals("left_alt", FillCombo.normalizeName("LEFT_ALT")),
                () -> assertEquals("left_alt", FillCombo.normalizeName("key.keyboard.left.alt")),
                () -> assertEquals("a", FillCombo.normalizeName("KEY_A")),
                () -> assertEquals("a", FillCombo.normalizeName("key.keyboard.a")),
                () -> assertEquals("", FillCombo.normalizeName(null)));
    }

    @Test
    @DisplayName("E4 只绑修饰键（没有鼠标键）：按着它点任意键都算，少按不算")
    void modifierOnlyCombo() {
        FillCombo.Combo onlyAlt = FillCombo.parse(List.of(KEY_LEFT_ALT_CODE), List.of("LEFT_ALT"));
        assertAll(
                () -> assertTrue(onlyAlt.usable()),
                () -> assertFalse(onlyAlt.hasMouse()),
                () -> assertFalse(onlyAlt.needsKeyboard(), "修饰键不算「键盘账本」那一类"),
                () -> assertTrue(FillCombo.matches(onlyAlt, BTN_LEFT_263, false, false, true)),
                () -> assertTrue(FillCombo.matches(onlyAlt, BTN_RIGHT_263, false, false, true)),
                () -> assertFalse(FillCombo.matches(onlyAlt, BTN_LEFT_263, false, false, false),
                        "Alt 没按着 ⇒ 判否"));
    }

    @Test
    @DisplayName("D6 修饰键子判据：少按判否、多按放行、不可用/null 恒否（日志据此分原因）")
    void modifiersMatchIsPrecise() {
        assertAll(
                () -> assertTrue(FillCombo.modifiersMatch(ctrlAltLeft(), false, true, true)),
                () -> assertTrue(FillCombo.modifiersMatch(ctrlAltLeft(), true, true, true),
                        "多按没绑的 Shift 不算错（allowExtraKeys）"),
                () -> assertFalse(FillCombo.modifiersMatch(ctrlAltLeft(), false, true, false)),
                () -> assertFalse(FillCombo.modifiersMatch(ctrlAltLeft(), false, false, true)),
                () -> assertFalse(FillCombo.modifiersMatch(ctrlAltLeft(), false, false, false)),
                () -> assertFalse(FillCombo.modifiersMatch(null, true, true, true)),
                () -> assertFalse(FillCombo.modifiersMatch(
                        FillCombo.parse(List.of(), List.of()), true, true, true)));
    }
}
