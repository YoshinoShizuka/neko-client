package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecipeBrowseModel} 的判据（纯逻辑，不启动游戏）。
 *
 * <h2>为什么这些值得单独测</h2>
 * <p>配方本的「浏览」在界面上看是一堆按钮，但真正会出错、且出错后最难查的是三件事：</p>
 * <ol>
 *   <li><b>页码夹取</b>：删一条之后总页数变少，客户端拿着旧页码去问是常态。
 *       夹错了会出现「第 9 / 4 页」，或者干脆负页码被服务端夹回第 1 页然后界面跳一下。</li>
 *   <li><b>页数至少 1</b>：返回 0 会让 {@code clampPage} 夹出 -1，然后渲染时下标崩。</li>
 *   <li><b>删除确认</b>：服务端是全局真删、没有撤销，误删由全服承担。
 *       确认窗口一旦写成「有 id 就删」，玩家点 A 再点 B 就会把 B 删掉。</li>
 * </ol>
 */
class RecipeBrowseModelTest {

    /** 固定窗口，避免测试里出现魔法数字 */
    private static final long WINDOW = RecipeBrowseModel.CONFIRM_WINDOW_MS;

    @Nested
    @DisplayName("A 页码夹取")
    class ClampPage {

        @Test
        @DisplayName("A1 正常范围内原样返回")
        void keepsValidPage() {
            assertAll(
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(0, 100, 8)),
                    () -> assertEquals(5, RecipeBrowseModel.clampPage(5, 100, 8)));
        }

        @Test
        @DisplayName("A2 负页码夹到 0")
        void negativeToZero() {
            assertAll(
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(-1, 100, 8)),
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(-999, 100, 8)));
        }

        @Test
        @DisplayName("A3 超出末页夹到末页（删完之后总页数变小就是这个场景）")
        void beyondLastToLast() {
            // 100 条 / 每页 8 ⇒ 13 页，合法下标 0..12
            assertEquals(12, RecipeBrowseModel.clampPage(12, 100, 8));
            assertEquals(12, RecipeBrowseModel.clampPage(13, 100, 8));
            assertEquals(12, RecipeBrowseModel.clampPage(999, 100, 8));
        }

        @Test
        @DisplayName("A4 空本子 / pageSize 非法时夹到 0，不返回负数")
        void emptyOrBadPageSize() {
            assertAll(
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(3, 0, 8)),
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(3, 10, 0)),
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(3, 10, -4)),
                    () -> assertEquals(0, RecipeBrowseModel.clampPage(-1, 0, 8)));
        }
    }

    @Nested
    @DisplayName("B 页数")
    class PageCount {

        @Test
        @DisplayName("B1 空本子也算 1 页（否则 clampPage 会夹出 -1）")
        void emptyIsOnePage() {
            assertAll(
                    () -> assertEquals(1, RecipeBrowseModel.pageCount(0, 8)),
                    () -> assertEquals(1, RecipeBrowseModel.pageCount(-5, 8)));
        }

        @Test
        @DisplayName("B2 整除与余数")
        void division() {
            assertAll(
                    () -> assertEquals(1, RecipeBrowseModel.pageCount(8, 8)),
                    () -> assertEquals(2, RecipeBrowseModel.pageCount(9, 8)),
                    () -> assertEquals(13, RecipeBrowseModel.pageCount(100, 8)),
                    () -> assertEquals(256, RecipeBrowseModel.pageCount(2048, 8)));
        }

        @Test
        @DisplayName("B3 pageSize 非法时不除零")
        void badPageSize() {
            assertAll(
                    () -> assertEquals(1, RecipeBrowseModel.pageCount(10, 0)),
                    () -> assertEquals(1, RecipeBrowseModel.pageCount(10, -1)));
        }
    }

    @Nested
    @DisplayName("C 文本")
    class Texts {

        @Test
        @DisplayName("C1 行标题恒带产出数（×1 也是信息）")
        void rowLabel() {
            assertAll(
                    () -> assertEquals("铁剑 ×1", RecipeBrowseModel.rowLabel("铁剑", 1)),
                    () -> assertEquals("木板 ×4", RecipeBrowseModel.rowLabel("木板", 4)),
                    // 服务端理论上不会给 0，但真给了也别显示「×0」
                    () -> assertEquals("铁剑 ×1", RecipeBrowseModel.rowLabel("铁剑", 0)));
        }

        @Test
        @DisplayName("C2 名字为空时给个占位，不留空行")
        void rowLabelFallback() {
            assertAll(
                    () -> assertEquals("？ ×2", RecipeBrowseModel.rowLabel(null, 2)),
                    () -> assertEquals("？ ×2", RecipeBrowseModel.rowLabel("  ", 2)));
        }

        @Test
        @DisplayName("C3 原料摘要")
        void partsLine() {
            assertAll(
                    () -> assertEquals("原料：铁锭 ×2、木棍 ×1",
                            RecipeBrowseModel.partsLine(List.of("铁锭 ×2", "木棍 ×1"))),
                    () -> assertEquals("原料：（无）", RecipeBrowseModel.partsLine(List.of())),
                    () -> assertEquals("原料：（无）", RecipeBrowseModel.partsLine(null)),
                    () -> assertEquals("原料：铁锭 ×2", RecipeBrowseModel.partsLine(List.of("铁锭 ×2"))));
        }

        @Test
        @DisplayName("C4 状态行：页码显示是 1 基，且用夹取后的值")
        void usageLine() {
            assertEquals("已用 137 / 2048 · 第 1 / 18 页 · 共 137 条",
                    RecipeBrowseModel.usageLine(137, 2048, 0, 137, 8));
            // 页码越界时显示末页，不显示「第 99 / 18 页」
            assertEquals("已用 137 / 2048 · 第 18 / 18 页 · 共 137 条",
                    RecipeBrowseModel.usageLine(137, 2048, 99, 137, 8));
            assertEquals("已用 0 / 2048 · 第 1 / 1 页 · 共 0 条",
                    RecipeBrowseModel.usageLine(0, 2048, 0, 0, 8));
        }
    }

    @Nested
    @DisplayName("D 删除二次确认")
    class Confirm {

        @Test
        @DisplayName("D1 第一次点只是 ARMED，不许直接删")
        void firstTapArms() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-a", 1000L, WINDOW));
            assertEquals("id-a", gate.pendingId());
        }

        @Test
        @DisplayName("D2 窗口内点同一条 ⇒ CONFIRMED，且清空待确认（不能连点删两次）")
        void secondTapConfirms() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            gate.tap("id-a", 1000L, WINDOW);
            assertEquals(RecipeBrowseModel.TapResult.CONFIRMED, gate.tap("id-a", 2000L, WINDOW));
            // 再来一次就是重新 ARMED（第二次删除必须重新确认）
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-a", 2100L, WINDOW));
        }

        @Test
        @DisplayName("D3 超过窗口 ⇒ 重新 ARMED，不会误删")
        void expiredWindowRearms() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            gate.tap("id-a", 1000L, WINDOW);
            assertEquals(RecipeBrowseModel.TapResult.ARMED,
                    gate.tap("id-a", 1000L + WINDOW + 1, WINDOW));
        }

        @Test
        @DisplayName("D4 窗口边界（正好等于窗口时长）仍算命中")
        void boundaryIsInclusive() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            gate.tap("id-a", 1000L, WINDOW);
            assertEquals(RecipeBrowseModel.TapResult.CONFIRMED,
                    gate.tap("id-a", 1000L + WINDOW, WINDOW));
        }

        @Test
        @DisplayName("D5 🔴 点 A 再点 B ⇒ B 只是 ARMED，绝不能被 A 的确认带上去")
        void differentTargetDoesNotConfirm() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-a", 1000L, WINDOW));
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-b", 1100L, WINDOW));
            assertEquals("id-b", gate.pendingId(), "待确认必须已经换成 B");
            // 此时再点 A 也只是 ARMED（A 已经不是待确认目标了）
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-a", 1200L, WINDOW));
        }

        @Test
        @DisplayName("D6 isArmed 只在窗口内、且是同一条时为真")
        void isArmed() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            assertFalse(gate.isArmed("id-a", 1000L, WINDOW), "还没点过就不该是红的");
            gate.tap("id-a", 1000L, WINDOW);
            assertAll(
                    () -> assertTrue(gate.isArmed("id-a", 1000L, WINDOW)),
                    () -> assertTrue(gate.isArmed("id-a", 1000L + WINDOW, WINDOW)),
                    () -> assertFalse(gate.isArmed("id-a", 1000L + WINDOW + 1, WINDOW)),
                    () -> assertFalse(gate.isArmed("id-b", 1000L, WINDOW)),
                    () -> assertFalse(gate.isArmed(null, 1000L, WINDOW)));
        }

        @Test
        @DisplayName("D7 clear / 空 id / null id")
        void clearAndBadIds() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            gate.tap("id-a", 1000L, WINDOW);
            gate.clear();
            assertFalse(gate.isArmed("id-a", 1000L, WINDOW));
            assertEquals(null, gate.pendingId());

            // null / 空串不建立待确认（否则界面会有一行莫名其妙变红）
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap(null, 1000L, WINDOW));
            assertEquals(null, gate.pendingId());
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("", 1000L, WINDOW));
            assertEquals(null, gate.pendingId());
        }

        @Test
        @DisplayName("D8 时间倒退（now 变小）按窗口外处理，不会误确认")
        void clockGoesBackwards() {
            RecipeBrowseModel.ConfirmGate gate = new RecipeBrowseModel.ConfirmGate();
            gate.tap("id-a", 5000L, WINDOW);
            assertEquals(RecipeBrowseModel.TapResult.ARMED, gate.tap("id-a", 1000L, WINDOW));
        }
    }
    /**
     * 阶段提示语。
     *
     * <h2>为什么这组也要测</h2>
     * <p>0.4.8 第一版的真机故障是界面<b>永远</b>停在「正在向服务端要配方本…」——
     * 代码全绿、单测全绿、jar 断言全绿，玩家看到的却是转圈。原因是服务端在闸门没过时
     * 静默丢包，而客户端把「没回包」当成了「还在等」。教训：<b>提示语是功能的一部分</b>，
     * 它必须说出「该去开哪个界面」和「服务端要什么版本」，而不能只是「请稍候」。</p>
     */
    @Nested
    @DisplayName("E 阶段提示语")
    class Hints {

        @Test
        @DisplayName("E1 没开着仓库时必须说清「先开虚空仓库」，并给出可执行的三步")
        void needContainerSaysWhatToOpen() {
            List<String> lines = RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.NEED_CONTAINER);
            String all = String.join("", lines);
            assertAll(
                    () -> assertFalse(lines.isEmpty(), "这一阶段不能没有话"),
                    () -> assertTrue(all.contains("虚空仓库"), "必须点名要先开哪个界面：\n" + all),
                    () -> assertTrue(all.contains("右键"), "必须给出可执行的动作，而不是「请检查设置」：\n" + all),
                    () -> assertTrue(all.contains("服务端"), "必须解释这是服务端的要求，否则玩家会去重装客户端"));
        }

        @Test
        @DisplayName("E2 服务端没回应时必须写明版本门槛 1.0.17 与两条规则")
        void noReplySaysVersionAndRules() {
            List<String> lines = RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.NO_REPLY);
            String all = String.join("", lines);
            assertAll(
                    () -> assertFalse(lines.isEmpty()),
                    () -> assertTrue(all.contains("1.0.17"), "不写版本门槛，玩家会怀疑是客户端装错了：\n" + all),
                    () -> assertTrue(all.contains("voidWarehouse") && all.contains("voidWarehouseCraft"),
                            "两条规则缺一不可，必须点名：\n" + all),
                    () -> assertTrue(all.contains("重试"), "要告诉玩家下一步能做什么：\n" + all));
        }

        @Test
        @DisplayName("E3 能正常浏览时不许占版面（READY / IDLE / UNSUPPORTED 都是空）")
        void readyHasNoHints() {
            assertAll(
                    () -> assertTrue(RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.READY).isEmpty()),
                    () -> assertTrue(RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.IDLE).isEmpty()),
                    () -> assertTrue(RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.UNSUPPORTED).isEmpty()),
                    () -> assertTrue(RecipeBrowseModel.hintLines(null).isEmpty(), "null 不许炸"));
        }

        @Test
        @DisplayName("E4 探测中有一行；任何阶段都不许出现「稍候」这种没有终点的句子")
        void probingSaysWaiting() {
            assertEquals(1, RecipeBrowseModel.hintLines(RecipeBrowseModel.Phase.PROBING).size());
            for (RecipeBrowseModel.Phase phase : RecipeBrowseModel.Phase.values()) {
                for (String line : RecipeBrowseModel.hintLines(phase)) {
                    assertFalse(line.contains("稍候"), phase + " 出现了没有终点的提示：" + line);
                    assertFalse(line.isBlank(), phase + " 有空行");
                }
            }
        }
    }
}
