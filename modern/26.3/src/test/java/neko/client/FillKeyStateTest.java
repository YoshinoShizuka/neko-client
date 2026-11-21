package neko.client;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FillKeyState} 的单测：<b>不启动游戏、不碰混入</b>。
 *
 * <p>它咬的是「取满支持单个键盘键」这件事的地基：如果账本记错了、或者松开的销账
 * 记错了，表现是「按着 R 点格子没反应」或者「松开了还一直算按着」—— 两种都<b>不会崩、
 * 不会报错</b>。上一轮（0.5.7）的同类问题就是靠「单键一律判否」静默失效的，
 * 这次把「按下 / 松开 / 清空 / 名字缺失」四种边角一起钉住。</p>
 */
class FillKeyStateTest {

    private static final int KEY_R = 340;
    private static final int KEY_F = 344;

    @BeforeEach
    void reset() {
        FillKeyState.clear();
    }

    @Test
    @DisplayName("K1 按下即按着、松开即销账（码值与键名两条都要销）")
    void pressAndRelease() {
        FillKeyState.press(KEY_R, "key.keyboard.r");
        boolean heldAfterPress = FillKeyState.heldCodes().contains(KEY_R)
                && FillKeyState.heldNames().contains("r");
        FillKeyState.release(KEY_R, "key.keyboard.r");
        assertAll(
                () -> assertTrue(heldAfterPress, "按下之后两条账都该在"),
                () -> assertFalse(FillKeyState.heldCodes().contains(KEY_R)),
                () -> assertFalse(FillKeyState.heldNames().contains("r"),
                        "键名不销掉 ⇒ 换个码值再按也永远算按着"),
                () -> assertTrue(FillKeyState.heldCodes().isEmpty()),
                () -> assertTrue(FillKeyState.heldNames().isEmpty()));
    }

    @Test
    @DisplayName("K2 键名缺失时只按码值对账，且不许把空串塞进名字集合")
    void nullNameIsHarmless() {
        FillKeyState.press(KEY_F, null);
        boolean polluted = FillKeyState.heldNames().contains("");
        FillKeyState.release(KEY_F, null);
        assertAll(
                () -> assertFalse(polluted, "空串进了名字集合 ⇒ 任何名字缺失的键都会互相「算按着」"),
                () -> assertTrue(FillKeyState.heldCodes().isEmpty()),
                () -> assertTrue(FillKeyState.heldNames().isEmpty(),
                        "release(code, null) 也必须把码值销掉"));
    }

    @Test
    @DisplayName("K3 多个键同时按着：只松开一个，另一个还在")
    void multipleKeys() {
        FillKeyState.press(KEY_R, "key.keyboard.r");
        FillKeyState.press(KEY_F, "key.keyboard.f");
        FillKeyState.release(KEY_R, "key.keyboard.r");
        assertAll(
                () -> assertFalse(FillKeyState.heldCodes().contains(KEY_R)),
                () -> assertTrue(FillKeyState.heldCodes().contains(KEY_F)),
                () -> assertTrue(FillKeyState.heldNames().contains("f")));
    }

    @Test
    @DisplayName("K4 clear() 必须清干净（界面关闭时靠它兜住「切窗口丢 key-up」）")
    void clearWipesEverything() {
        FillKeyState.press(KEY_R, "key.keyboard.r");
        FillKeyState.clear();
        assertAll(
                () -> assertTrue(FillKeyState.heldCodes().isEmpty()),
                () -> assertTrue(FillKeyState.heldNames().isEmpty()),
                () -> assertEquals("（没有键按下）", FillKeyState.describe()),
                () -> assertTrue(FillKeyState.sawAnyPress(),
                        "sawAnyPress 是「这个会话收过按键没有」的事实，不该被 clear 抹掉 —— "
                                + "否则「混入没挂上」与「刚清过账本」就分不开了"));
    }

    @Test
    @DisplayName("K5 describe() 给人看的名字，不是码值；空账本给一句人话")
    void describeIsReadable() {
        assertAll(
                () -> assertEquals("（没有键按下）", FillKeyState.describe()),
                () -> {
                    FillKeyState.press(KEY_R, "key.keyboard.r");
                    assertEquals("r", FillKeyState.describe());
                },
                () -> {
                    FillKeyState.press(KEY_F, null);
                    assertTrue(FillKeyState.describe().contains("码" + KEY_F),
                            "没有名字时就写码值，别留空白让人猜");
                });
    }
}
