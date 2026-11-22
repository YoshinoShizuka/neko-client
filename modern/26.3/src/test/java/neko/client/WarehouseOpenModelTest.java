package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「按键打开虚空仓库」的纯判据单测：不启动游戏、不碰网络、不碰 malilib。
 *
 * <p>这条链路最难查的故障不是发包，而是「回执该不该理」和「该不该说话」。
 * 迟到回执一旦被当成这一次的结果，玩家连按两下就会看到上一局那句「未启用」；
 * 提示不做节流，规则关着时每按一次都刷一行。两件事都在这里钉死。</p>
 */
class WarehouseOpenModelTest {

    // ------------------------------------------------------------ A 组：nonce 配对

    @Test
    @DisplayName("A1 同 nonce 的回执必须采信")
    void matchingNonceIsAccepted() {
        assertFalse(WarehouseOpenModel.isLate(7, 7));
    }

    @Test
    @DisplayName("A2 🔴 nonce 不同就是迟到 —— 连按两下时旧回执不能冒充新结果")
    void differentNonceIsLate() {
        assertAll(
                () -> assertTrue(WarehouseOpenModel.isLate(6, 7), "上一次的回执必须丢掉"),
                () -> assertTrue(WarehouseOpenModel.isLate(8, 7), "超前回执也不能采信"));
    }

    @Test
    @DisplayName("A3 🔴 还没发过包（lastSent=-1）时任何回执都不许采信")
    void noRequestMeansEveryReplyIsLate() {
        assertAll(
                () -> assertTrue(WarehouseOpenModel.isLate(1, -1)),
                () -> assertTrue(WarehouseOpenModel.isLate(0, -1)),
                () -> assertTrue(WarehouseOpenModel.isLate(-1, -1)));
    }

    // ------------------------------------------------------------ B 组：状态 → 提示

    @Test
    @DisplayName("B1 RULE_OFF / UNAVAILABLE 要给动作栏提示，键必须落在实际语言文件里")
    void failureStatusesHaveMessages() {
        assertAll(
                () -> assertEquals("message.neko_client.open_warehouse_rule_off",
                        WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_RULE_OFF)),
                () -> assertEquals("message.neko_client.open_warehouse_unavailable",
                        WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_UNAVAILABLE)));
    }

    @Test
    @DisplayName("B2 OK / ALREADY_OPEN / THROTTLED / BAD_REQUEST / NONE 一律静默")
    void silentStatuses() {
        assertAll(
                () -> assertNull(WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_OK)),
                () -> assertNull(WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_ALREADY_OPEN)),
                () -> assertNull(WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_THROTTLED)),
                () -> assertNull(WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_BAD_REQUEST)),
                () -> assertNull(WarehouseOpenModel.messageKeyFor(WarehouseOpenModel.STATUS_NONE)),
                () -> assertNull(WarehouseOpenModel.messageKeyFor(999), "未知状态也不许说话"));
    }

    // ------------------------------------------------------------ C 组：节流

    @Test
    @DisplayName("C1 第一次提示不节流；3 秒内闭嘴；超过 3 秒重新允许")
    void ruleOffCooldown() {
        long now = 100_000L;
        long cooldown = WarehouseOpenModel.RULE_OFF_NOTICE_COOLDOWN_MS;
        assertAll(
                () -> assertFalse(WarehouseOpenModel.shouldThrottleRuleOff(now, 0L),
                        "从没提示过必须放行"),
                () -> assertTrue(WarehouseOpenModel.shouldThrottleRuleOff(now, now - 1L)),
                () -> assertTrue(WarehouseOpenModel.shouldThrottleRuleOff(now, now - cooldown + 1L),
                        "差 1 ms 也要闭嘴"),
                () -> assertFalse(WarehouseOpenModel.shouldThrottleRuleOff(now, now - cooldown),
                        "刚好满 3 秒要放行"));
    }

    @Test
    @DisplayName("C2 🔴 被挡下的那一次不能推时间戳 —— 否则连按会把静默期一直续下去")
    void throttledCallMustNotAdvanceClock() {
        long first = 1_000L;
        assertAll(
                () -> assertFalse(WarehouseOpenModel.shouldThrottleRuleOff(first, 0L),
                        "第一次必须提示，调用方据此记下 first"),
                () -> assertTrue(WarehouseOpenModel.shouldThrottleRuleOff(first + 1_000L, first)),
                () -> assertFalse(WarehouseOpenModel.shouldThrottleRuleOff(first + 4_000L, first),
                        "若把被挡下的那次时间戳也更新，这里会假红"));
    }

    // ------------------------------------------------------------ D 组：界面门禁

    @Test
    @DisplayName("D1 界面开着不响应；只有游戏内（screen == null）才发请求")
    void onlyOutsideGui() {
        assertAll(
                () -> assertFalse(WarehouseOpenModel.canRequest(true),
                        "界面开着的按键属于界面输入，必须让路给搜索框等快捷键"),
                () -> assertTrue(WarehouseOpenModel.canRequest(false)));
    }
}
