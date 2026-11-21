package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WarehouseTakeModel} 的单测：纯整数判据，不启动游戏。
 *
 * <p>咬的是「成功要取交集」这类写歪了照样能编译、只在真机上表现为
 * 「提示说取到了、背包里却没多东西」的判据。</p>
 */
class WarehouseTakeModelTest {

    @Test
    @DisplayName("A1 OK + 背包件数真的变多 ⇒ 成功")
    void deliveredNeedsBoth() {
        assertAll(
                () -> assertTrue(WarehouseTakeModel.delivered(0, 3, 67)),
                () -> assertTrue(WarehouseTakeModel.delivered(0, 0, 64)));
    }

    @Test
    @DisplayName("A2 🔴 OK 但背包没变多 / 变少 ⇒ 不算成功（专用服务器上的跨进程确认）")
    void okWithoutGainIsNotDelivery() {
        assertAll(
                () -> assertFalse(WarehouseTakeModel.delivered(0, 5, 5), "OK 但没到货"),
                () -> assertFalse(WarehouseTakeModel.delivered(0, 5, 3), "OK 但反而少了"));
    }

    @Test
    @DisplayName("A3 非 OK 状态即便件数变多也不算这次成功（可能是别人的货 / 迟到回执）")
    void nonOkNeverDelivers() {
        for (int status : new int[]{1, 2, 3, 4, 5, -2}) {
            assertFalse(WarehouseTakeModel.delivered(status, 0, 64), "status=" + status);
        }
    }

    @Test
    @DisplayName("A3b 🔴 没有回执时的兜底：背包真的变多就算到货（回执被 printer 抢走时的唯一活路）")
    void deliveredCountWithoutReplyUsesInventoryOnly() {
        assertAll(
                () -> assertEquals(64, WarehouseTakeModel.deliveredCountWithoutReply(0, 64)),
                () -> assertEquals(20, WarehouseTakeModel.deliveredCountWithoutReply(44, 64)),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCountWithoutReply(5, 5), "没变多就是没到货"),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCountWithoutReply(64, 3), "变少不算负数"));
    }

    @Test
    @DisplayName("A4 deliveredCount 给出「这次到手几件」：0 / 部分 / 溢出边界")
    void deliveredCountReportsGain() {        assertAll(
                () -> assertEquals(64, WarehouseTakeModel.deliveredCount(0, 0, 64)),
                () -> assertEquals(20, WarehouseTakeModel.deliveredCount(0, 4, 24)),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCount(0, 5, 5), "OK 但没动"),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCount(0, 9, 2), "变少不算负数"),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCount(2, 0, 64), "EMPTY 时背包变多与仓库无关"),
                () -> assertEquals(0, WarehouseTakeModel.deliveredCount(3, 0, 64), "INVENTORY_FULL 不许记成到手"));
    }

    @Test
    @DisplayName("B1 只有 THROTTLED 才重试，其它失败态不重试")
    void retryOnlyThrottled() {
        assertAll(
                () -> assertTrue(WarehouseTakeModel.shouldRetry(4)),
                () -> assertFalse(WarehouseTakeModel.shouldRetry(2)),
                () -> assertFalse(WarehouseTakeModel.shouldRetry(0)),
                () -> assertFalse(WarehouseTakeModel.shouldRetry(-2)));
    }

    @Test
    @DisplayName("C1 每种失败态都有动作栏提示键；成功与还没回执不提示")
    void hintKeys() {
        assertAll(
                () -> assertEquals("message.neko_client.warehouse_take_rule_off",
                        WarehouseTakeModel.hintKey(1)),
                () -> assertEquals("message.neko_client.warehouse_take_empty",
                        WarehouseTakeModel.hintKey(2)),
                () -> assertEquals("message.neko_client.warehouse_take_full",
                        WarehouseTakeModel.hintKey(3)),
                () -> assertEquals("message.neko_client.warehouse_take_throttled",
                        WarehouseTakeModel.hintKey(4)),
                () -> assertEquals("message.neko_client.warehouse_take_bad_request",
                        WarehouseTakeModel.hintKey(5)),
                () -> assertEquals("message.neko_client.warehouse_take_no_reply",
                        WarehouseTakeModel.hintKey(-2)),
                () -> assertNull(WarehouseTakeModel.hintKey(0), "成功由服务端那条消息负责，不重复提示"),
                () -> assertNull(WarehouseTakeModel.hintKey(99), "未知状态码不说话，别刷屏"));
    }

    @Test
    @DisplayName("D1 nonce 递增且绕回时跳过 0（0 是哨兵）")
    void nonceSkipsZero() {
        assertAll(
                () -> assertEquals(2, WarehouseTakeModel.nextNonce(1)),
                () -> assertEquals(100, WarehouseTakeModel.nextNonce(99)),
                () -> assertEquals(1, WarehouseTakeModel.nextNonce(Integer.MAX_VALUE)));
    }

    @Test
    @DisplayName("D2 状态码常量与服务端 PrinterTakeResultPayload 对齐")
    void statusConstantsMatchServer() {
        assertAll(
                () -> assertEquals(0, WarehouseTakeModel.STATUS_OK),
                () -> assertEquals(1, WarehouseTakeModel.STATUS_RULE_OFF),
                () -> assertEquals(2, WarehouseTakeModel.STATUS_EMPTY),
                () -> assertEquals(3, WarehouseTakeModel.STATUS_INVENTORY_FULL),
                () -> assertEquals(4, WarehouseTakeModel.STATUS_THROTTLED),
                () -> assertEquals(5, WarehouseTakeModel.STATUS_BAD_REQUEST));
    }
}
