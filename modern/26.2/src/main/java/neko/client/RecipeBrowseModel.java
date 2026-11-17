package neko.client;

import java.util.List;

/**
 * 配方本浏览的<b>纯逻辑</b>：分页夹取、行文本、删除二次确认状态机。
 *
 * <p>刻意不 import 任何 MC / malilib 类型（连 payload 的 {@code Entry} 也不碰）——
 * 这样它能直接跑 JUnit，而不需要启动游戏。跨版本容易出错的从来不是「发什么包」，
 * 而是「页码越界怎么夹」「删一条要点几次」「同一条的确认窗口算不算过期」这些
 * 与图形库无关的判断，把它们钉在这里最划算。</p>
 *
 * <h2>为什么页大小是 8</h2>
 * <p>界面高 240：标题 20 + 状态行 12 + 8 行 × 22 + 底部按钮 24 ≈ 232。
 * 服务端允许到 32，但一屏放不下 32 行，多要的条目只会被丢掉 —— 8 行是「一屏能看清」
 * 与「少翻页」的折中。{@link #SERVER_MAX_PAGE_SIZE} 只是提醒别把 pageSize 报超。</p>
 *
 * <h2>为什么需要「点两次才删」</h2>
 * <p>服务端把删除做成<b>全局真删</b>（一份本子全服共用），点错一次的代价是所有人少一条配方，
 * 而且没有撤销。协议里刻意没有 {@code undoToken}（那要改成两段式状态机），
 * 所以确认这一步必须由界面负责 —— 但状态必须能被单测咬住，不能散落在 GUI 回调里。</p>
 */
public final class RecipeBrowseModel {

    /** 一屏放得下的行数（界面几何决定，见类注释） */
    public static final int PAGE_SIZE = 8;

    /** 服务端 {@code RecipeQueryPayload.MAX_PAGE_SIZE} 的同值副本：报超了会被夹，别指望它 */
    public static final int SERVER_MAX_PAGE_SIZE = 32;

    /** 二次确认窗口：3 秒内再点同一条才真删 */
    public static final long CONFIRM_WINDOW_MS = 3000L;

    private RecipeBrowseModel() {
    }

    /**
     * 把页码夹到合法范围。
     *
     * <p>删除之后总页数会变小，客户端拿着旧页码来问是常态（服务端也做同样的夹取，
     * 见 {@code RecipeQueryPayload} 的头注释）。这里先夹一次是为了界面显示正确 ——
     * 否则会出现「第 9 / 4 页」这种自相矛盾的标题。</p>
     *
     * @param page     客户端当前页码（0 基，可能为负）
     * @param total    过滤后的总条数（服务端回的）
     * @param pageSize 每页条数（≤0 视为 1）
     * @return 合法页码，恒 ≥ 0
     */
    public static int clampPage(int page, int total, int pageSize) {
        int last = pageCount(total, pageSize) - 1;
        if (page < 0) {
            return 0;
        }
        return Math.min(page, last);
    }

    /**
     * 总页数，<b>至少 1</b>。
     *
     * <p>返回 0 会让界面出现「第 1 / 0 页」，也会让 {@link #clampPage} 夹出 -1。
     * 空本子显示「1 / 1」比显示「0 / 0」更像人话。</p>
     */
    public static int pageCount(int total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }

    /** 行标题：{@code 铁剑 ×1}。产出数恒显示 —— 「×1」也是信息（一次产几个） */
    public static String rowLabel(String displayName, int outputCount) {
        String name = (displayName == null || displayName.isBlank()) ? "？" : displayName;
        return name + " ×" + Math.max(1, outputCount);
    }

    /**
     * 原料摘要：{@code 原料：铁锭 ×2、木棍 ×1}。
     *
     * @param renderedParts 已经渲染好的「名字 ×数量」列表（名字解析在调用方，那里才有语言表）
     */
    public static String partsLine(List<String> renderedParts) {
        if (renderedParts == null || renderedParts.isEmpty()) {
            return "原料：（无）";
        }
        return "原料：" + String.join("、", renderedParts);
    }

    /** 顶部状态行：{@code 已用 137 / 2048 · 第 1 / 18 页 · 共 137 条} */
    public static String usageLine(int used, int max, int page, int total, int pageSize) {
        return "已用 " + used + " / " + max
                + " · 第 " + (clampPage(page, total, pageSize) + 1) + " / " + pageCount(total, pageSize) + " 页"
                + " · 共 " + Math.max(0, total) + " 条";
    }

    /** 一次点击的结果 */
    public enum TapResult {
        /** 只是「准备好删了」：界面该把这一行标红、提示再点一次 */
        ARMED,
        /** 第二次点击落在窗口内：可以发包了 */
        CONFIRMED
    }

    /**
     * 删除的二次确认闸门。
     *
     * <p>语义只有三条，且全部可测：</p>
     * <ol>
     *   <li>第一次点某条 ⇒ {@link TapResult#ARMED}；</li>
     *   <li>窗口内再点<b>同一条</b> ⇒ {@link TapResult#CONFIRMED}，并清空待确认（不可连点删两次）；</li>
     *   <li>点了<b>另一条</b>，或窗口已过 ⇒ 重新 {@code ARMED}，旧的那条自然失效。</li>
     * </ol>
     *
     * <p>🔴 不用「全局一个布尔 + 一个 id」的两个字段：那样很容易出现「id 换了但布尔没换」，
     * 于是点 A 一次、点 B 一次就把 B 删了 —— 这类 bug 在真机上表现为「偶尔删错」。</p>
     */
    public static final class ConfirmGate {

        private String pendingId;
        private long armedAt;

        /**
         * 记一次点击。
         *
         * @param recipeId 被点的配方 id（opaque）
         * @param now      当前时刻（毫秒，调用方给，便于单测注入）
         * @param windowMs 确认窗口
         */
        public TapResult tap(String recipeId, long now, long windowMs) {
            if (recipeId == null || recipeId.isEmpty()) {
                return TapResult.ARMED;
            }
            // 🔴 三个条件都要：同一条 + 时间没倒退 + 还在窗口内。
            // 少了「时间没倒退」，系统时钟被回拨（NTP 校时、玩家改时间）时 now-armedAt 为负，
            // 负数恒 ≤ windowMs ⇒ 一次点击就把配方删了。D8 就是钉这个。
            boolean sameTarget = recipeId.equals(this.pendingId)
                    && now >= this.armedAt
                    && (now - this.armedAt) <= windowMs;
            if (sameTarget) {
                this.pendingId = null;
                return TapResult.CONFIRMED;
            }
            this.pendingId = recipeId;
            this.armedAt = now;
            return TapResult.ARMED;
        }

        /** 这一行现在是不是「等着再点一次」的状态（界面据此标红） */
        public boolean isArmed(String recipeId, long now, long windowMs) {
            return recipeId != null && recipeId.equals(this.pendingId)
                    && now >= this.armedAt
                    && (now - this.armedAt) <= windowMs;
        }

        /** 关闭界面 / 换页 / 收到广播时清掉，避免「上次的确认残留到新列表」 */
        public void clear() {
            this.pendingId = null;
            this.armedAt = 0L;
        }

        /** 只给单测看：当前待确认的 id，没有则 null */
        String pendingId() {
            return this.pendingId;
        }
    }
}
