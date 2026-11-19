package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link VoidWarehouseChatFilter} 的单测：<b>不启动游戏、不碰 MC 类</b>。
 *
 * <p>为什么这些判据必须能单测：静音这类功能有个很坏的失效模式 ——
 * <b>开关明明打开了，却一条都没拦住</b>，而界面上没有任何提示说「判据没命中」。
 * 0.4.3 的取满组合键就在真机上以这个形状活了好几轮。把判据拆成纯文本类，
 * 这里把服务端真实的三种文本形状钉死，改前缀 / 改续行 / 改成 contains 都会红。</p>
 */
class VoidWarehouseChatFilterTest {

    // ------------------------------------------------------------ A 组：必须命中

    @Test
    @DisplayName("A1 中文主提示前缀（carpet VoidWarehouseText.INFO_PREFIX）")
    void chinesePrefixMatches() {
        assertTrue(VoidWarehouseChatFilter.shouldSilence("[虚空仓库] 已取出 64 个铁锭。"));
    }

    @Test
    @DisplayName("A2 英文主提示前缀（carpet VoidWarehouseText.INFO_PREFIX_EN）")
    void englishPrefixMatches() {
        assertTrue(VoidWarehouseChatFilter.shouldSilence("[Void Warehouse] Withdrew 64 iron ingots."));
    }

    @Test
    @DisplayName("A3 配方本续行 └ 开头、不带前缀，也必须静音（否则漏半截消息）")
    void continuationMatches() {
        assertAll(
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("└ 还能这样合：木板 × 4")),
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("└ Not clicking means it is NOT recorded.")));
    }

    @Test
    @DisplayName("A4 前缀前挂着前导空白 / §x 样式码仍命中（getString 通常不带，但要防字面量颜色码）")
    void leadingDecorationsAreStripped() {
        assertAll(
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("  [虚空仓库] 库存不足")),
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("§6[虚空仓库] 库存不足")),
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("§6§l [Void Warehouse] No stock")),
                () -> assertTrue(VoidWarehouseChatFilter.shouldSilence("\n└ 容量已满")));
    }

    @Test
    @DisplayName("A5 一条消息内部带续行（\\\\n└ ...）时，主前缀已经让整条命中")
    void embeddedContinuationIsCoveredByPrefix() {
        assertTrue(VoidWarehouseChatFilter.shouldSilence("[虚空仓库] 已记住配方\n└ 不点就不会记住。"));
    }

    // ------------------------------------------------------------ B 组：不许命中

    @Test
    @DisplayName("B1 普通聊天与其他模组的消息一律放行")
    void ordinaryChatPasses() {
        assertAll(
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("玩家1: 大家好")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("[系统] 服务器将在 5 分钟后重启")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("§7[其他模组] 一条提示")));
    }

    @Test
    @DisplayName("B2 🔴 前缀出现在句中不算：玩家引用虚空仓库的话不能被一起吃掉（禁用 contains）")
    void prefixMustBeAtStart() {
        assertAll(
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("玩家1: [虚空仓库] 库存不足")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("刚才那句 [Void Warehouse] 是什么意思")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("看这里 ↓ └ 这一行")));
    }

    @Test
    @DisplayName("B3 空串 / null 不许命中（判据不能因为空值抛异常）")
    void nullAndEmptyPass() {
        assertAll(
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence(null)),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("   ")));
    }

    @Test
    @DisplayName("B4 长得像但不是那三条前缀 ⇒ 放行（大小写 / 括号都不能省）")
    void lookalikesPass() {
        assertAll(
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("虚空仓库 库存不足")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("[void warehouse] No stock")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("[虚空仓库]库存不足")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("├ 别的模组的续行")),
                () -> assertFalse(VoidWarehouseChatFilter.shouldSilence("└")));
    }
}
