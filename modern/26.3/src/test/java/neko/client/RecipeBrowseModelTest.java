package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link RecipeBrowseModel} 的判据（纯逻辑，不启动游戏）。
 *
 * <h2>为什么这些值得单独测</h2>
 * <p>配方本的「浏览」在界面上看是一堆按钮，但真正会出错、且出错后最难查的是这几件事：</p>
 * <ol>
 *   <li><b>页码夹取</b>：删一条之后总页数变少，客户端拿着旧页码去问是常态。
 *       夹错了会把负页码递给服务端，然后界面跳一下。</li>
 *   <li><b>页数至少 1</b>：返回 0 会让 {@code clampPage} 夹出 -1，然后渲染时下标崩。</li>
 *   <li><b>删除确认</b>：服务端是全局真删、没有撤销，误删由全服承担。
 *       确认窗口一旦写成「有 id 就删」，玩家点 A 再点 B 就会把 B 删掉。</li>
 *   <li><b>搜索匹配度</b>（0.5.0）：匹配度就是「置顶」的依据。分错级的话玩家搜「铁剑」，
 *       第一屏却是「铁块」—— 功能看着能用，实际不能用。</li>
 *   <li><b>滚动预取</b>（0.5.0）：判早了 = 一开界面白拉一堆包；判晚了 = 滚到底卡住。
 *       边界两侧各咬一次。</li>
 *   <li><b>搜索防抖</b>（0.5.0）：不防抖就等于每敲一个字发一包，服务端 50ms 限流会把前一次的
 *       请求挤掉，玩家看到的是「搜索结果和输入框对不上」。</li>
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
        @DisplayName("C4 状态行不再报页码，改报「已显示 X / Y 条」")
        void statusLine() {
            assertAll(
                    () -> assertEquals("已用 137 / 2048 · 已显示 32 / 137 条",
                            RecipeBrowseModel.statusLine(137, 2048, 32, 137, "")),
                    () -> assertEquals("已用 0 / 2048 · 已显示 0 / 0 条",
                            RecipeBrowseModel.statusLine(0, 2048, 0, 0, "")),
                    // 搜索态要能一眼看到「筛出了多少」
                    () -> assertEquals("已用 137 / 2048 · 搜索「铁」：8 / 12 条",
                            RecipeBrowseModel.statusLine(137, 2048, 8, 12, "铁")),
                    () -> assertEquals("已用 137 / 2048 · 搜索「铁」：没有匹配",
                            RecipeBrowseModel.statusLine(137, 2048, 0, 0, "铁")),
                    // null 搜索词 = 浏览态，别抛 NPE
                    () -> assertEquals("已用 1 / 2048 · 已显示 0 / 5 条",
                            RecipeBrowseModel.statusLine(1, 2048, 0, 5, null)));
        }

        @Test
        @DisplayName("C5 状态行里永远不许再出现「页」")
        void statusLineNeverSpeaksPages() {
            // 0.5.0 把翻页按钮删了。再出现「第 N / M 页」就是在指一个不存在的功能。
            assertAll(
                    () -> assertFalse(RecipeBrowseModel.statusLine(1, 2048, 8, 12, "铁").contains("页")),
                    () -> assertFalse(RecipeBrowseModel.statusLine(1, 2048, 8, 12, "").contains("页")));
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
        @DisplayName("E1 服务端没回应时必须写明版本门槛 1.0.17 与两条规则，并给出下一步")
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
        @DisplayName("E2 任何提示语都不许再要求「先打开仓库界面」（服务端 1.0.17 已取消该要求）")
        void noPhaseSendsPlayerToTheWarehouse() {
            // 🔴 0.4.8 的自我设限就是从这里长出来的：客户端以为服务端只认开着的仓库界面，
            // 于是「没开着容器 ⇒ 连 hello 都不发」。服务端早就不要求了，提示语也不许再这么写。
            for (RecipeBrowseModel.Phase phase : RecipeBrowseModel.Phase.values()) {
                for (String line : RecipeBrowseModel.hintLines(phase)) {
                    assertAll(
                            () -> assertFalse(line.contains("打开仓库"), phase + " 还在让玩家先去开仓库：" + line),
                            () -> assertFalse(line.contains("开着仓库"), phase + " 还在把仓库界面当条件：" + line),
                            () -> assertFalse(line.contains("虚空仓库界面"), phase + " 还在要求仓库界面：" + line));
                }
            }
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

    @Nested
    @DisplayName("F 搜索匹配度（决定谁置顶）")
    class Rank {

        @Test
        @DisplayName("F1 四级匹配度：完全相等 > 前缀 > 包含 > 只命中 id")
        void fourLevels() {
            assertAll(
                    () -> assertEquals(RecipeBrowseModel.RANK_EXACT,
                            RecipeBrowseModel.matchRank("铁剑", "铁剑", "minecraft:iron_sword")),
                    () -> assertEquals(RecipeBrowseModel.RANK_PREFIX,
                            RecipeBrowseModel.matchRank("铁", "铁剑", "minecraft:iron_sword")),
                    () -> assertEquals(RecipeBrowseModel.RANK_CONTAINS,
                            RecipeBrowseModel.matchRank("剑", "铁剑", "minecraft:iron_sword")),
                    () -> assertEquals(RecipeBrowseModel.RANK_ID,
                            RecipeBrowseModel.matchRank("sword", "铁剑", "minecraft:iron_sword")),
                    () -> assertEquals(RecipeBrowseModel.RANK_NONE,
                            RecipeBrowseModel.matchRank("钻石", "铁剑", "minecraft:iron_sword")));
        }

        @Test
        @DisplayName("F2 大小写与首尾空白都不影响（用 Locale.ROOT，不受系统语言影响）")
        void normalizesInput() {
            assertAll(
                    () -> assertEquals(RecipeBrowseModel.RANK_EXACT,
                            RecipeBrowseModel.matchRank("  IRON  ", "Iron", null)),
                    () -> assertEquals(RecipeBrowseModel.RANK_PREFIX,
                            RecipeBrowseModel.matchRank(" Oak ", "Oak Planks", "minecraft:oak_planks")),
                    () -> assertEquals("iron", RecipeBrowseModel.normalizeQuery("  IRON ")),
                    () -> assertEquals("", RecipeBrowseModel.normalizeQuery(null)));
        }

        @Test
        @DisplayName("F3 id 搜索不用带命名空间")
        void idWithoutNamespace() {
            assertAll(
                    () -> assertEquals("oak_planks", RecipeBrowseModel.bareId("minecraft:oak_planks")),
                    () -> assertEquals("oak_planks", RecipeBrowseModel.bareId("oak_planks")),
                    () -> assertEquals("", RecipeBrowseModel.bareId(null)),
                    // 玩家打「oak」而显示名是中文时，靠 id 兜住
                    () -> assertEquals(RecipeBrowseModel.RANK_ID,
                            RecipeBrowseModel.matchRank("oak", "橡木木板", "minecraft:oak_planks")));
        }

        @Test
        @DisplayName("F4 显示名为 null 时退回 id 的 path，不许把这条判成「没匹配」")
        void nullNameFallsBackToId() {
            // 客户端装了服务端没有的模组时 displayNameOf 返回 null —— 这时按 id 还能搜到
            assertEquals(RecipeBrowseModel.RANK_CONTAINS,
                    RecipeBrowseModel.matchRank("planks", null, "minecraft:oak_planks"));
        }

        @Test
        @DisplayName("F5 空搜索词 = 不过滤，恒 RANK_EXACT")
        void emptyQueryMatchesEverything() {
            assertAll(
                    () -> assertEquals(RecipeBrowseModel.RANK_EXACT,
                            RecipeBrowseModel.matchRank("", "随便什么", "minecraft:x")),
                    () -> assertEquals(RecipeBrowseModel.RANK_EXACT,
                            RecipeBrowseModel.matchRank(null, null, null)),
                    () -> assertTrue(RecipeBrowseModel.matches("", "铁剑", "minecraft:iron_sword")),
                    () -> assertFalse(RecipeBrowseModel.matches("钻石", "铁剑", "minecraft:iron_sword")));
        }

        @Test
        @DisplayName("F6 排序结果：完全匹配的那条必须排在「只是 id 命中」的前面")
        void exactBeatsIdHit() {
            // 这就是「置顶」的判据：玩家搜「铁剑」时，叫「铁剑」的那条要在最上面，
            // 而不是让「铁块（id 里有 iron）」压在它前面。
            int exact = RecipeBrowseModel.matchRank("铁剑", "铁剑", "minecraft:iron_sword");
            int prefix = RecipeBrowseModel.matchRank("铁剑", "铁剑碎片", "minecraft:iron_sword_shard");
            int idOnly = RecipeBrowseModel.matchRank("铁剑", "铁块", "minecraft:iron_sword_block");
            assertTrue(exact < prefix, "完全匹配应排在前面缀之前");
            assertTrue(prefix < idOnly, "名字命中应排在只命中 id 之前");
        }
    }

    @Nested
    @DisplayName("G 滚动预取（0.5.0 不再翻页）")
    class Prefetch {

        @Test
        @DisplayName("G1 还差得远时不预取")
        void farFromBottomDoesNotPrefetch() {
            // 32 条已加载，只画到第 3 行 ⇒ 不该再要下一页
            assertFalse(RecipeBrowseModel.shouldPrefetch(3, 32, 137));
        }

        @Test
        @DisplayName("G2 画到最后 PREFETCH_ROWS 行之内就该去要下一页")
        void nearBottomPrefetches() {
            int loaded = 32;
            int lastRow = loaded - RecipeBrowseModel.PREFETCH_ROWS - 1;
            assertTrue(RecipeBrowseModel.shouldPrefetch(lastRow, loaded, 137));
            // 再往回一行就不该触发（边界两侧各咬一次）
            assertFalse(RecipeBrowseModel.shouldPrefetch(lastRow - 1, loaded, 137));
        }

        @Test
        @DisplayName("G3 已经全部加载、或本来就没内容时，绝不预取")
        void stopsAtEnd() {
            assertAll(
                    () -> assertFalse(RecipeBrowseModel.shouldPrefetch(31, 32, 32), "已加载完"),
                    () -> assertFalse(RecipeBrowseModel.shouldPrefetch(0, 0, 137), "一条都没有"),
                    () -> assertFalse(RecipeBrowseModel.shouldPrefetch(5, 40, 10), "加载超了总数"),
                    // 一行都没画（-1）但已经有数据：仍按「差 6 行」算，别把首次加载卡住
                    () -> assertFalse(RecipeBrowseModel.shouldPrefetch(-1, 32, 137)));
        }
    }

    @Nested
    @DisplayName("H 搜索防抖（每敲一个字都发包会把服务端打爆）")
    class Debounce {

        @Test
        @DisplayName("H1 刚敲完不立刻发，停够时间才发一次")
        void waitsForTypingToStop() {
            RecipeBrowseModel.SearchDebounce box = new RecipeBrowseModel.SearchDebounce();
            assertNull(box.poll("铁", 1000L, 250L));
            // 打第二个字：重新计时
            assertNull(box.poll("铁剑", 1100L, 250L));
            // 还在计时窗口内
            assertNull(box.poll("铁剑", 1200L, 250L));
            // 停够了才吐出来
            assertEquals("铁剑", box.poll("铁剑", 1360L, 250L));
            // 已经应用过，再轮询不该重复发
            assertNull(box.poll("铁剑", 5000L, 250L));
            assertEquals("铁剑", box.applied());
        }

        @Test
        @DisplayName("H2 清空输入框也要走一次（否则列表卡在上一次的结果里）")
        void clearingAlsoFires() {
            RecipeBrowseModel.SearchDebounce box = new RecipeBrowseModel.SearchDebounce();
            box.poll("铁", 0L, 250L);
            assertEquals("铁", box.poll("铁", 300L, 250L));
            assertNull(box.poll("", 400L, 250L));
            assertEquals("", box.poll("", 700L, 250L));
        }

        @Test
        @DisplayName("H3 reset 之后不会把旧词再吐一遍（关界面重开时用）")
        void resetSwallowsOldText() {
            RecipeBrowseModel.SearchDebounce box = new RecipeBrowseModel.SearchDebounce();
            box.poll("铁", 0L, 250L);
            assertEquals("铁", box.poll("铁", 300L, 250L));
            box.reset("铁");
            assertNull(box.poll("铁", 10_000L, 250L), "reset 过就不该再发一次");
            assertEquals("铁", box.applied());
        }
    }
}
