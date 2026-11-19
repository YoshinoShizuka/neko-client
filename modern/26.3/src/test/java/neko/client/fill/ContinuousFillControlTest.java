package neko.client.fill;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 连续填充热键的纯状态：按一下开、再按一下关，断线/关规则可复位。 */
class ContinuousFillControlTest {

    @Test
    @DisplayName("A1 默认关闭；toggle 两次回到关闭")
    void toggles() {
        ContinuousFillControl.disarm();
        assertFalse(ContinuousFillControl.isArmed());
        assertAll(
                () -> assertTrue(ContinuousFillControl.toggle()),
                () -> assertTrue(ContinuousFillControl.isArmed()),
                () -> assertFalse(ContinuousFillControl.toggle()),
                () -> assertFalse(ContinuousFillControl.isArmed()));
    }

    @Test
    @DisplayName("A2 关规则/断线时复位，避免下次右键意外触发连续填充")
    void disarm() {
        ContinuousFillControl.toggle();
        ContinuousFillControl.disarm();
        assertFalse(ContinuousFillControl.isArmed());
    }
}
