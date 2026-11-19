package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 统一提示前缀的语言判据：中文玩家看 [虚空仓库]，英文玩家看 [Void Warehouse]。 */
class WarehouseTextTest {

    @Test
    @DisplayName("A1 中英语言码各自选对前缀，缺语言码时回落中文")
    void prefixLanguage() {
        assertAll(
                () -> assertFalse(WarehouseText.isEnglishCode("zh_cn")),
                () -> assertFalse(WarehouseText.isEnglishCode("zh_tw")),
                () -> assertTrue(WarehouseText.isEnglishCode("en_us")),
                () -> assertTrue(WarehouseText.isEnglishCode("en_gb")),
                () -> assertTrue(WarehouseText.isEnglishCode("EN_US"), "大小写不该影响"),
                () -> assertFalse(WarehouseText.isEnglishCode(""), "空串回落中文"),
                () -> assertFalse(WarehouseText.isEnglishCode(null), "缺语言码回落中文"));
    }
}
