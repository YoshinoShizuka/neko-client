package neko.client;

import java.util.List;
import java.util.Locale;

/**
 * 配方本浏览的<b>纯逻辑</b>：搜索匹配与排序、滚动预取判据、行文本、删除二次确认状态机。
 *
 * <p>刻意不 import 任何 MC / malilib 类型（连 payload 的 {@code Entry} 也不碰）——
 * 这样它能直接跑 JUnit，而不需要启动游戏。跨版本容易出错的从来不是「发什么包」，
 * 而是「搜索该把哪条排在最前」「滚到第几行该去要下一页」「删一条要点几次」这些
 * 与图形库无关的判断，把它们钉在这里最划算。</p>
 *
 * <h2>0.5.0：为什么从「翻页」改成「滚到底自动续」</h2>
 * <p>服务端是分页的（一页最多 32 条），但玩家要看的是<b>一整本</b>：
 * 0.4.x 那两个「上一页 / 下一页」按钮让「找一条配方」变成「逐个猜页码」，
 * 而且一页只有 8 条时，137 条的本子要按 18 次。现在的做法是：</p>
 * <ol>
 *   <li>{@link #PAGE_SIZE} 一次要满 32 条（服务端上限），列表连续滚动；</li>
 *   <li>滚到「池底 - 预取裕量」时就悄悄要下一页，用户看到的是「越滚越多」；
 *       裕量按<b>当前屏幕能显示几行</b>现算（0.5.1 起，不再写死）；</li>
 *   <li>页码完全不进界面 —— 玩家不需要知道它存在（{@link #statusLine} 只报「已显示 X / Y 条」）。</li>
 * </ol>
 *
 * <h2>0.5.0：搜索为什么必须是「匹配度排序」</h2>
 * <p>服务端只认<b>产物 id 白名单</b>（它没有中文语言表，见 {@code RecipeQueryPayload} 头注释），
 * 所以匹配一定发生在客户端。但「匹配上了」不等于「玩家想要的那条」：
 * 搜「铁」时，{@code 铁剑} 和其它 40 个带铁的产物都会进白名单，而服务端只按 id 字典序返回 ——
 * 玩家第一屏看到的可能是「铁块」。{@link #matchRank} 给出的分数就是<b>置顶依据</b>：
 * 名字完全相等 &gt; 名字前缀 &gt; 名字包含 &gt; 拼音（含模糊拼音）&gt; id 包含，
 * 同分再按名字和 recipeId 排。</p>
 *
 * <h2>0.5.1：模糊拼音怎么折</h2>
 * <p>zh/ch/sh 归平舌、前后鼻音归一（ing/ang/eng）、n/l/r 与 f/h 各归一组
 * （见 {@link #fuzzyKey}）。两边（查询串与拼音 token）都折到同一个键上再比，
 * 而不是把查询展开成变体逐个试 —— 归一化是 O(n)，展开是组合爆炸。</p>
 *
 * <h2>为什么需要「点两次才删」</h2>
 * <p>服务端把删除做成<b>全局真删</b>（一份本子全服共用），点错一次的代价是所有人少一条配方，
 * 而且没有撤销。协议里刻意没有 {@code undoToken}（那要改成两段式状态机），
 * 所以确认这一步必须由界面负责 —— 但状态必须能被单测咬住，不能散落在 GUI 回调里。</p>
 */
public final class RecipeBrowseModel {

    /**
     * 一次向服务端要多少条。
     *
     * <p>0.4.x 这里是 8（一屏放得下），现在列表是连续滚动的，一次多要几条能少跑好几个来回。
     * 值等于服务端 {@code RecipeQueryPayload.MAX_PAGE_SIZE} —— 报超了会被服务端夹，
     * 而 {@code recipe_page} 的解码上限也是同一个数，两边必须一致。</p>
     */
    public static final int PAGE_SIZE = 32;

    /** 服务端 {@code RecipeQueryPayload.MAX_PAGE_SIZE} 的同值副本：报超了会被夹，别指望它 */
    public static final int SERVER_MAX_PAGE_SIZE = 32;

    /**
     * 一行的高度（像素）。列表行高与「可见行数」的换算都靠它 —— 改了这里，
     * {@code WidgetListRecipe} 与界面的可见行推算会跟着变（这正是 0.5.1 的主题：
     * <b>一次显示多少条不再写死</b>，按当前屏幕高度除以行高现算）。
     */
    public static final int ENTRY_HEIGHT = 24;

    /**
     * 预取裕量的下限（行）。
     *
     * <p>0.5.0 写死 6 行；0.5.1 改成 {@code max(这个值, 可见行数 / 2)} ——
     * 屏幕越高，要提前拉的就越多，否则大屏滚到底会「停下等包」。</p>
     */
    public static final int PREFETCH_MIN_ROWS = 4;

    /**
     * 搜索框停止输入多久之后才真去问服务端。
     *
     * <p>每敲一个字都发一次 {@code recipe_query}，服务端有 50 ms 限流 + 一次全表排序，
     * 打「铁剑」两个字就会把前一次的请求挤掉。250 ms 让「连续打字」只发最后一次。</p>
     */
    public static final long SEARCH_DEBOUNCE_MS = 250L;

    /** 二次确认窗口：3 秒内再点同一条才真删 */
    public static final long CONFIRM_WINDOW_MS = 3000L;

    /** 匹配度：显示名与搜索词完全相等（最该置顶） */
    public static final int RANK_EXACT = 0;
    /** 匹配度：显示名以搜索词开头 */
    public static final int RANK_PREFIX = 1;
    /** 匹配度：显示名包含搜索词 */
    public static final int RANK_CONTAINS = 2;
    /** 匹配度：拼音 / 首字母 / 模糊拼音命中（对中文用户，这比 id 命中更有用） */
    public static final int RANK_PINYIN = 3;
    /** 匹配度：只有物品 id 包含搜索词（玩家看到的可能是完全不相干的中文名） */
    public static final int RANK_ID = 4;
    /** 完全没匹配上 */
    public static final int RANK_NONE = Integer.MAX_VALUE;

    private RecipeBrowseModel() {
    }

    // ------------------------------------------------------------------ 搜索

    /**
     * 规范化搜索词：去首尾空白 + 小写。
     *
     * <p>用 {@link Locale#ROOT} 而不是默认区域：土耳其语区域会把 {@code I} 小写成 {@code ı}，
     * 于是「Iron」在那边搜不到 {@code iron}。这类 bug 只在特定系统语言下复现，最难查。</p>
     */
    public static String normalizeQuery(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT);
    }

    /** 去掉 {@code minecraft:} 前缀：玩家搜「oak」时不该被命名空间挡住 */
    public static String bareId(String itemId) {
        if (itemId == null) {
            return "";
        }
        int colon = itemId.indexOf(':');
        return colon >= 0 ? itemId.substring(colon + 1) : itemId;
    }

    /**
     * 一条配方对搜索词的匹配度，<b>越小越该置顶</b>。
     *
     * <p>没有拼音 token 的场合（调用方还没建索引）用三参重载，拼音档永远不命中。</p>
     *
     * @param query       玩家输入的搜索词（可为空 = 不过滤，恒 {@link #RANK_EXACT}）
     * @param displayName 客户端本地化显示名（可能为 null：客户端不认识这个 id）
     * @param itemId      产物物品 id（全名或裸 path 都行）
     * @return {@link #RANK_EXACT} … {@link #RANK_ID}，或 {@link #RANK_NONE}
     */
    public static int matchRank(String query, String displayName, String itemId) {
        return matchRank(query, displayName, itemId, "", "");
    }

    /**
     * 同上，但带拼音 token（0.5.1 起支持拼音 / 首字母 / <b>模糊拼音</b>）。
     *
     * <p>档位顺序是有讲究的：拼音命中排在 id 命中<b>之前</b> —— 对中文用户，
     * 「打 tieding 命中铁锭」比「打 ingot 命中」更接近他想要的东西。</p>
     *
     * @param pinyinFull     显示名的全拼（无声调、无分隔，{@code 铁锭 → tieding}；空串 = 没有）
     * @param pinyinInitials 显示名的拼音首字母（{@code 铁锭 → td}；空串 = 没有）
     */
    public static int matchRank(String query, String displayName, String itemId,
                                String pinyinFull, String pinyinInitials) {
        String q = normalizeQuery(query);
        if (q.isEmpty()) {
            return RANK_EXACT;
        }
        String name = normalizeQuery(displayName);
        // 显示名解析不出来时拿 id 的 path 顶上：至少还能按 id 搜到，比整条消失好
        if (name.isEmpty()) {
            name = normalizeQuery(bareId(itemId));
        }
        if (name.equals(q)) {
            return RANK_EXACT;
        }
        if (name.startsWith(q)) {
            return RANK_PREFIX;
        }
        if (name.contains(q)) {
            return RANK_CONTAINS;
        }
        if (pinyinMatches(q, pinyinFull, pinyinInitials)) {
            return RANK_PINYIN;
        }
        String bare = normalizeQuery(bareId(itemId));
        String full = normalizeQuery(itemId);
        if ((!bare.isEmpty() && bare.contains(q)) || (!full.isEmpty() && full.contains(q))) {
            return RANK_ID;
        }
        return RANK_NONE;
    }

    /** 是否匹配（界面/白名单只需要这个布尔；带拼音 token 的版本） */
    public static boolean matches(String query, String displayName, String itemId,
                                  String pinyinFull, String pinyinInitials) {
        return matchRank(query, displayName, itemId, pinyinFull, pinyinInitials) != RANK_NONE;
    }

    /** 是否匹配（界面/白名单只需要这个布尔；无拼音 token） */
    public static boolean matches(String query, String displayName, String itemId) {
        return matchRank(query, displayName, itemId) != RANK_NONE;
    }

    /**
     * 拼音这条路上是否命中（全拼与首字母都试，两边都过 {@link #fuzzyKey}）。
     *
     * <p>全拼用 {@code startsWith} 或 {@code contains}：拼音串是按字拼起来的、没有分隔符，
     * 边界处会误伤（{@code tiesi} 里含 {@code esi}），这是模糊搜索本来就要接受的噪音。</p>
     */
    private static boolean pinyinMatches(String query, String pinyinFull, String pinyinInitials) {
        String q = fuzzyKey(query);
        if (q.isEmpty()) {
            return false;
        }
        String full = fuzzyKey(pinyinFull);
        String initials = fuzzyKey(pinyinInitials);
        if (full.isEmpty() && initials.isEmpty()) {
            return false;
        }
        return (!full.isEmpty() && (full.startsWith(q) || full.contains(q)))
                || (!initials.isEmpty() && (initials.startsWith(q) || initials.contains(q)));
    }

    /**
     * 🔴 模糊拼音的<b>归一化</b>：把容易混的声母 / 韵母折到同一个键上，两边都折，
     * 之后普通字符串比较就行 —— 不需要把查询展开成一组变体再逐个试。
     *
     * <p>折法（右边的键为「标准音」）：</p>
     * <ul>
     *   <li>翘舌归平：{@code zh→z}、{@code ch→c}、{@code sh→s}</li>
     *   <li>前后鼻音：{@code ing→in}、{@code ang→an}、{@code eng→en}</li>
     *   <li>声母混读：{@code r→l→n}（n/l/r 一组）、{@code f→h}（f/h 一组）</li>
     * </ul>
     *
     * <p>顺序很重要：双字先换（否则 {@code zh} 会先被拆成 {@code z}+{@code h}），
     * {@code r→l} 必须在 {@code l→n} 之前（否则 r 折不到 n 上）。代价是「日 / 热」这类字
     * 会和 n 组互串 —— 真实输入法的模糊音就是这么设计的，玩家要的正是这个。</p>
     */
    public static String fuzzyKey(String raw) {
        String s = normalizeQuery(raw);
        if (s.isEmpty()) {
            return "";
        }
        // 双字组合必须在单字之前
        s = s.replace("zh", "z").replace("ch", "c").replace("sh", "s");
        s = s.replace("ing", "in").replace("ang", "an").replace("eng", "en");
        // 单字混读：r 先折到 l，再一起折到 n
        s = s.replace('r', 'l').replace('l', 'n').replace('f', 'h');
        return s;
    }

    /**
     * 滚到第几行时该去要下一页。
     *
     * <p>0.5.1 起<b>裕量按屏幕现算</b>：屏幕能显示越多行，就要越早开始预取，
     * 否则大屏滚到底会出现「停下等包」。不再写死一个行数。</p>
     *
     * @param lastVisibleRow 当前画出来的最后一行的下标（0 基；没有任何行时传 -1）
     * @param loaded         已经拿到的条数
     * @param total          服务端说一共有多少条（白名单过滤后的）
     * @param visibleRows    当前屏幕能完整显示的行数（界面按 {@link #ENTRY_HEIGHT} 现算）
     * @return {@code true} = 该去要下一页
     */
    public static boolean shouldPrefetch(int lastVisibleRow, int loaded, int total, int visibleRows) {
        if (loaded <= 0 || loaded >= total) {
            return false;
        }
        int rows = Math.max(1, visibleRows);
        int margin = Math.max(PREFETCH_MIN_ROWS, rows / 2);
        return lastVisibleRow + 1 + margin >= loaded;
    }

    /**
     * 搜索框防抖：把「每帧轮询到的输入」翻译成「这一刻该应用的搜索词」。
     *
     * <p>为什么不用定时器：界面每帧都会轮询一次输入框，这个类只做三个判断 ——
     * 变了就重新计时、没变且已应用就什么都不做、停够 {@code delayMs} 才吐出新词。
     * 全部由调用方传时钟，所以能在单测里精确到毫秒地验。</p>
     */
    public static final class SearchDebounce {

        private String pending = "";
        private long pendingAt;
        private String applied = "";

        /**
         * @param raw     输入框里此刻的文字（原样，内部会规范化）
         * @param now     当前时刻（毫秒）
         * @param delayMs 停多久才算「打完了」
         * @return 该应用的搜索词；{@code null} = 这一帧什么都不用做
         */
        public String poll(String raw, long now, long delayMs) {
            String norm = normalizeQuery(raw);
            if (!norm.equals(this.pending)) {
                this.pending = norm;
                this.pendingAt = now;
                return null;
            }
            if (norm.equals(this.applied)) {
                return null;
            }
            if (now - this.pendingAt < delayMs) {
                return null;
            }
            this.applied = norm;
            return norm;
        }

        /** 已经应用的搜索词（界面拿它显示「搜索中」） */
        public String applied() {
            return this.applied;
        }

        /** 强制同步（关界面 / 断线重开）：下一次 {@link #poll} 会立刻返回这个词 */
        public void reset(String value) {
            this.pending = normalizeQuery(value);
            this.applied = this.pending;
            this.pendingAt = 0L;
        }
    }

    // ------------------------------------------------------------------ 分页

    /**
     * 把页码夹到合法范围。
     *
     * <p>删除之后总页数会变小，客户端拿着旧页码来问是常态（服务端也做同样的夹取，
     * 见 {@code RecipeQueryPayload} 的头注释）。这里先夹一次是为了不发出明显越界的请求。</p>
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
     * <p>返回 0 会让 {@link #clampPage} 夹出 -1。</p>
     */
    public static int pageCount(int total, int pageSize) {
        if (total <= 0 || pageSize <= 0) {
            return 1;
        }
        return (total + pageSize - 1) / pageSize;
    }

    // ------------------------------------------------------------------ 行文本

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

    /**
     * 顶部状态行。
     *
     * <p>0.5.0 起<b>不再显示页码</b>：列表连续滚动，报「第 3 / 18 页」只会让玩家去找
     * 那个已经不存在的翻页按钮。改报「已显示 32 / 137 条」，滚到底会自己涨。</p>
     *
     * @param used    本子实际占用条数（服务端 {@code index.used}）
     * @param max     上限（2048）
     * @param loaded  客户端手上已有多少条
     * @param total   服务端说这份筛选下一共有多少条
     * @param query   当前搜索词（空 = 没在搜索）
     */
    public static String statusLine(int used, int max, int loaded, int total, String query) {
        String head = "已用 " + Math.max(0, used) + " / " + max;
        String q = normalizeQuery(query);
        int shownTotal = Math.max(0, total);
        if (q.isEmpty()) {
            return head + " · 已显示 " + Math.max(0, loaded) + " / " + shownTotal + " 条";
        }
        if (shownTotal == 0) {
            return head + " · 搜索「" + q + "」：没有匹配";
        }
        return head + " · 搜索「" + q + "」：" + Math.max(0, loaded) + " / " + shownTotal + " 条";
    }

    /** 一次点击的结果 */
    public enum TapResult {
        /** 只是「准备好删了」：界面该把这一行标红、提示再点一次 */
        ARMED,
        /** 第二次点击落在窗口内：可以发包了 */
        CONFIRMED
    }

    /**
     * 能力探测阶段。
     *
     * <p>放在这里（而不是 {@link RecipeClientState}）是为了让「每个阶段说什么话」能被单测咬住 ——
     * 0.4.8 第一版的真机故障就是界面永远停在 {@link #PROBING}，而那条提示语没有任何断言。</p>
     *
     * <h2>🔴 为什么只有「没回音」一种失败（0.4.9 更正）</h2>
     * <p>曾经有过一个 {@code NEED_CONTAINER} 态：客户端自己看 {@code containerMenu} 是不是原版背包
     * 菜单，不是就<b>连 hello 都不发</b>，理由是「服务端只认正开着的仓库界面，发了也是白发」。
     * <b>那个前提在服务端 1.0.17 就没了</b>（{@code RecipeNetwork#dataOf} 现在只判规则与数据能不能取到），
     * 于是它变成一条纯自我设限：玩家在世界上按热键 ⇒ 客户端判「没开着容器」⇒ 一个包都不发 ⇒
     * 界面永远空着，而服务端其实会回。</p>
     * <p>现在的判据只有一条：<b>发出去，等 2 秒</b>。没回音 = 服务端版本旧 / 规则关着 / 数据取不到，
     * 三种都不需要客户端替它猜。</p>
     */
    public enum Phase {
        /** 还什么都没问（没打开过界面 / 刚断线） */
        IDLE,
        /** 发过 hello，还没等到 index */
        PROBING,
        /** 拿到过 index，可以浏览 */
        READY,
        /** hello 发出去了，2 秒内一个回包都没有（服务端版本旧 / 规则关着 / 数据取不到） */
        NO_REPLY,
        /** 对端没有这条通道（旧服务端 / 纯原版服）⇒ 入口不该出现 */
        UNSUPPORTED
    }

    /**
     * 每个非浏览阶段该说什么。
     *
     * <p>三条硬要求：① 说清<b>要满足什么条件</b>（服务端版本 + 哪两条规则），不要只说「请检查设置」；
     * ② 必须给出<b>下一步能做什么</b>（点「重试」）；③ 不许出现「请稍候」这种没有终点的句子 ——
     * 玩家分不出「稍候」和「永远」。</p>
     *
     * <p>🔴 也不许再写「先去打开虚空仓库界面」：那条要求服务端已经取消（见 {@link Phase}）。</p>
     */
    public static List<String> hintLines(Phase phase) {
        if (phase == null) {
            return List.of();
        }
        return switch (phase) {
            case PROBING -> List.of("正在向服务端要配方本…");
            case NO_REPLY -> List.of(
                    "服务端没回应：需要 carpet-Neko-Addition 1.0.17+，且 voidWarehouse 与 "
                            + "voidWarehouseCraft 两条规则都开着",
                    "规则已经开着的话，点「重试」再问一次（改规则不用重进世界）");
            case IDLE, READY, UNSUPPORTED -> List.of();
        };
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
