package neko.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import neko.client.RecipeBrowseModel.Phase;
import neko.voidwarehouse.recipe.RecipeChangedPayload;
import neko.voidwarehouse.recipe.RecipeDeletePayload;
import neko.voidwarehouse.recipe.RecipeDeleteResultPayload;
import neko.voidwarehouse.recipe.RecipeHelloPayload;
import neko.voidwarehouse.recipe.RecipeIndexPayload;
import neko.voidwarehouse.recipe.RecipePagePayload;
import neko.voidwarehouse.recipe.RecipeQueryPayload;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 配方本（服务端那本全服共用的）在客户端的状态机：探测能力、按需拉页、搜索、删条目。
 *
 * <h2>一句话说清它管什么</h2>
 * <p>服务端只给「索引 / 分页 / 删除 / 变更广播」四件事（见
 * {@code carpet-Neko-Addition} 的 {@code docs/方案-配方本浏览与删除（服务端）.md}）。
 * 这里负责把它们接起来，并把结果摊平成界面能直接读的字段 —— <b>不做任何名字匹配</b>，
 * 那是 {@link SearchCatalogResolver}（含拼音 token，0.5.1 起支持模糊拼音）与
 * {@link RecipeBrowseModel#matchRank} 的事（只有客户端有中文语言表）。</p>
 *
 * <h2>0.5.0：从「一页 8 条 + 翻页」改成「累加池 + 滚到底续拉」</h2>
 * <p>{@link #entries} 现在是<b>累积</b>的：第 0 页替换、后续页追加（按 {@code recipeId} 去重，
 * 因为两次请求之间别人可能学或删了一条，服务端的分页窗口会滑动）。界面因此可以连续滚动，
 * 页码也不再出现在界面上。谁来触发下一页：</p>
 * <ul>
 *   <li>浏览态：{@link #noteVisibleRow} 记下画出来的最后一行，快到池底时 {@link #prefetchIfNeeded} 续拉；</li>
 *   <li>搜索态：<b>不等滚动</b>，直接把白名单里的页拉完 —— 否则「匹配度置顶」只能在这一页内成立。</li>
 * </ul>
 *
 * <h2>三条硬性做法（都不是随便定的）</h2>
 * <ol>
 *   <li><b>发之前过两道门禁</b>：{@link PayloadChannels#usable} 查归属、
 *       {@link ClientPlayNetworking#canSend} 查对端。撞名的若是别的类，那一发会在编码阶段
 *       炸在 netty 线程上，{@code try/catch} 抓不到，玩家只会看到断线。</li>
 *   <li><b>回包只改字段 + 自增 {@link #revision}</b>，绝不在这里碰控件。
 *       界面每帧比对 revision，变了就 {@code refreshEntries()} —— 这样不需要关心回调在哪个线程。</li>
 *   <li><b>删除必须点两次</b>（{@link RecipeBrowseModel.ConfirmGate}）。服务端是全局真删、
 *       没有撤销，误删的代价由全服承担。</li>
 * </ol>
 *
 * <h2>为什么 generation 是缓存键</h2>
 * <p>别人学了一条配方、或管理员删了一条，服务端都会把 {@code generation} 加一。
 * 客户端手里那些页此刻就已经过期了 —— 与其做增量补丁（服务端也没打算发），
 * 不如丢掉重取。见 {@link #onChanged}。</p>
 *
 * <h2>🔴 空白名单 ≠ 搜不到</h2>
 * <p>服务端把「白名单为空」定义成<b>不过滤</b>（与 {@code search_apply} 同义）。所以
 * 「搜到了 0 个产物」绝不能发一个空白名单出去 —— 那会把整本发回来，玩家看到的
 * 就是「搜索没生效」。这种情况由客户端拦住：{@link #setSearch} 里直接置空并说话。</p>
 */
public final class RecipeClientState {

    /**
     * 配方本上限，与服务端 {@code VoidWarehouseRecipeBook.MAX_RECIPES} 同值。
     *
     * <p>客户端只拿它显示「已用 N / 2048」，不参与任何判断 —— 真正的上限在服务端。
     * 抄一份常量的代价是「服务端改了这里会显示错」，但那只是一个数字，比引服务端类
     * （客户端根本加载不到）现实。</p>
     */
    public static final int MAX_RECIPES = 2048;

    /**
     * 审计日志（与 {@link PayloadChannels} 同一个 logger 名）。
     *
     * <p>🔴 加它是因为「界面空白」这种故障从日志里一个字都看不出来：0.4.9 两轮真机
     * （先是没有自动取页、后是控件没装数据）都得靠猜。索引/分页/删除/搜索都是低频动作，
     * 一次会话几行，不影响日志可读性。</p>
     */
    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /**
     * 能力探测阶段 —— 枚举与「每个阶段说什么」都在 {@link RecipeBrowseModel} 里。
     *
     * <p>这样 {@code hintLines} 能被单测覆盖（第一版永远转圈的故障，坏在提示语没有任何断言）。</p>
     */
    private static Phase phase = Phase.IDLE;

    private static final long HELLO_INTERVAL_MS = 100L;
    private static final long QUERY_INTERVAL_MS = 50L;
    private static final long DELETE_INTERVAL_MS = 1000L;

    /**
     * 发出去之后等多久算「服务端没回应」（hello 与 query 共用）。
     *
     * <p>服务端是<b>同步</b>处理这两条的（主线程收包、当场回），正常往返在几十毫秒内。
     * 2 秒已经给了 20 倍余量 —— 超过还没回，就不是网络慢，而是包被服务端丢了。</p>
     */
    private static final long REPLY_TIMEOUT_MS = 2000L;

    /**
     * 「索引说有条目、可第一页迟迟没到」时，最多自动补要几次。
     *
     * <p>2 次够覆盖一次丢包；再多就成了每 2 秒一包地刷服务端。</p>
     */
    private static final int MAX_PAGE_RETRIES = 2;

    /**
     * 搜索态最多自动拉多少页。
     *
     * <p>搜索是「拉完才能按匹配度置顶」，所以不等滚动。但白名单极端情况（搜「a」命中上千个产物）
     * 会变成 32 页连发 —— 32 × 32 = 1024 条，已经是「这么多结果你该换个词」的量级了。</p>
     */
    private static final int MAX_SEARCH_PAGES = 32;

    private static int generation = -1;
    private static int total;
    private static int used;
    private static boolean canDelete;
    private static List<String> outputs = List.of();
    /** 累积池：第 0 页替换、后续页追加（见类注释） */
    private static List<RecipePagePayload.Entry> entries = List.of();
    /** 下一个该要的页码；由服务端回的 {@code page + 1} 决定，不自己算（分页窗口会滑动） */
    private static int nextPage;
    /** 已经没有更多页了（服务端回了空页，或已够 {@link #total} 条） */
    private static boolean exhausted;
    private static int pageSize = RecipeBrowseModel.PAGE_SIZE;
    private static int sort = RecipeQueryPayload.SORT_OUTPUT;
    private static List<String> whitelist = List.of();
    /** 当前生效的搜索词（规范化后；空 = 浏览态） */
    private static String search = "";
    /** 界面画出来的最后一行下标（0 基）-1 = 一行都没画 */
    private static int lastVisibleRow = -1;
    /**
     * 当前屏幕能完整显示几行。界面按「列表高度 / 行高」每帧现算并塞进来 ——
     * 预取裕量跟着它走，<b>不再写死</b>：大屏提前拉得多，小屏少拉点省流量。
     * 默认 10 只是没收到界面报告时的兜底。
     */
    private static int visibleRows = 10;
    private static String status = "";
    private static int revision;

    private static final RecipeBrowseModel.ConfirmGate GATE = new RecipeBrowseModel.ConfirmGate();
    private static final RecipeBrowseModel.SearchDebounce DEBOUNCE = new RecipeBrowseModel.SearchDebounce();

    private static long lastHelloAt;
    /** 最近一次 hello 发出的时刻，用于 {@link #tickProbe} 判超时 */
    private static long helloSentAt;
    private static long lastQueryAt;
    /**
     * 最近一次 query 发出的时刻；{@code 0} = 没有待决的请求。
     *
     * <p>存在的理由和 {@link #helloSentAt} 一样：服务端在闸门没过时是静默丢包的，
     * 没有这个字段，玩家滚到底只会看到「不再加载」—— 又是一种「没反应」。</p>
     */
    private static long pendingQueryAt;
    /** 本次索引之后已经自动补要过几次第一页（见 {@link #ensureLoaded()}） */
    private static int pageRetries;
    private static long lastDeleteAt;

    private RecipeClientState() {
    }

    // ------------------------------------------------------------------ 读侧

    public static Phase phase() {
        return phase;
    }

    /** 界面该不该开：对端连通道都没有时直接不给入口，而不是开一个永远空着的界面 */
    public static boolean supported() {
        return phase != Phase.UNSUPPORTED;
    }

    public static int total() {
        return total;
    }

    public static int used() {
        return used;
    }

    public static boolean canDelete() {
        return canDelete;
    }

    public static List<String> outputs() {
        return outputs;
    }

    public static List<RecipePagePayload.Entry> entries() {
        return entries;
    }

    public static int pageSize() {
        return pageSize;
    }

    public static int sort() {
        return sort;
    }

    public static List<String> whitelist() {
        return whitelist;
    }

    /** 当前生效的搜索词（空 = 浏览态） */
    public static String search() {
        return search;
    }

    /** 还有没有没拉的页（界面据此显示「加载中」） */
    public static boolean hasMore() {
        return !exhausted && entries.size() < total;
    }

    public static String status() {
        return status;
    }

    /** 每收到一个回包自增；界面靠它决定要不要刷新控件 */
    public static int revision() {
        return revision;
    }

    public static int generation() {
        return generation;
    }

    // ------------------------------------------------------------------ 请求侧

    /**
     * 界面刚打开：要一份索引（顺带探测对端支不支持）。
     *
     * <p>🔴 <b>不做任何前置判断</b>。曾经在这里判过 {@code containerMenu}（「没开着仓库就别发」），
     * 而服务端 1.0.17 起已经不要求开着仓库界面（{@code RecipeNetwork#dataOf} 只判规则与数据）——
     * 那条判断只剩「把一个本来会成功的请求挡在客户端」这一个效果。要判的只有一处：发出去、等 2 秒。</p>
     */
    public static void open() {
        status = "";
        GATE.clear();
        pendingQueryAt = 0L;
        DEBOUNCE.reset(search);
        if (phase == Phase.NO_REPLY) {
            // 重开界面就是一次「再试一次」：别让玩家非得去找重试按钮
            phase = Phase.IDLE;
            lastHelloAt = 0L;
        }
        hello();
    }

    /** 发 hello（100ms 限流，与服务的 100ms 对齐） */
    public static void hello() {
        long now = System.currentTimeMillis();
        if (now - lastHelloAt < HELLO_INTERVAL_MS && phase != Phase.IDLE) {
            return;
        }
        lastHelloAt = now;
        if (!PayloadChannels.usable(RecipeHelloPayload.TYPE)
                || !ClientPlayNetworking.canSend(RecipeHelloPayload.TYPE)) {
            // 对端没有这条通道：安静地记一句，界面入口自然不出现（不刷屏、不断线）
            phase = Phase.UNSUPPORTED;
            revision++;
            return;
        }
        phase = Phase.PROBING;
        helloSentAt = now;
        ClientPlayNetworking.send(RecipeHelloPayload.INSTANCE);
        revision++;
    }

    /**
     * 要某一页（50ms 限流）。
     *
     * <p>🔴 页码不在这里夹、也不在本地记：服务端夹完会回夹后的值，{@link #onPage} 以回包为准。
     * 本地先改会显示「第 2 页」而内容是第 1 页的 —— 那是最难查的一种谎话。</p>
     */
    public static void requestPage(int target) {
        long now = System.currentTimeMillis();
        if (now - lastQueryAt < QUERY_INTERVAL_MS) {
            return;
        }
        if (!PayloadChannels.usable(RecipeQueryPayload.TYPE)
                || !ClientPlayNetworking.canSend(RecipeQueryPayload.TYPE)) {
            phase = Phase.UNSUPPORTED;
            revision++;
            return;
        }
        lastQueryAt = now;
        pendingQueryAt = now;
        ClientPlayNetworking.send(new RecipeQueryPayload(Math.max(0, target), pageSize, sort, whitelist));
        revision++;
    }

    /**
     * 丢掉手上的池，从第 0 页重来（换搜索词 / 换排序 / 删除回执 / 变更广播都走这里）。
     */
    public static void reload() {
        entries = List.of();
        nextPage = 0;
        exhausted = false;
        lastVisibleRow = -1;
        pageRetries = 0;
        pendingQueryAt = 0L;
        revision++;
        if (phase != Phase.READY) {
            // 连索引都还没有：等 onIndex 接这一拳（它会自己 requestPage(0)）
            return;
        }
        if (!search.isEmpty() && whitelist.isEmpty()) {
            // 🔴 见类注释「空白名单 ≠ 搜不到」：发出去会把整本拉回来
            total = 0;
            exhausted = true;
            status = "没有匹配的配方";
            revision++;
            return;
        }
        requestPage(0);
    }

    /**
     * 界面每帧喂一次搜索框原文：停手 {@link RecipeBrowseModel#SEARCH_DEBOUNCE_MS} 之后才真去查。
     *
     * <p>为什么在界面帧里轮询而不是监听键盘：搜索框是 malilib 的控件，它的文字变化没有回调
     * 暴露给我们；每帧比一次字符串是唯一跨版本安全的做法（也不碰任何输入事件类型）。</p>
     */
    public static void pollSearch(String raw) {
        String applied = DEBOUNCE.poll(raw, System.currentTimeMillis(),
                RecipeBrowseModel.SEARCH_DEBOUNCE_MS);
        if (applied != null) {
            setSearch(applied);
        }
    }

    /**
     * 应用一个搜索词：重算产物白名单并从头拉。
     *
     * <p>白名单是<b>发给服务端</b>的产物 id 集合 —— 名字匹配已经在客户端做完
     * （专用服务器没有中文语言表，服务端没法把「铁锭」对上号）。</p>
     */
    public static void setSearch(String raw) {
        String q = RecipeBrowseModel.normalizeQuery(raw);
        if (q.equals(search)) {
            return;
        }
        search = q;
        whitelist = buildWhitelist(q);
        LOGGER.info("[recipe] search \"{}\": {} / {} 个产物命中", q, whitelist.size(), outputs.size());
        reload();
    }

    /** 「清空搜索」按钮：把输入框和应用态一起清掉 */
    public static void clearSearch() {
        DEBOUNCE.reset("");
        setSearch("");
    }

    /** 换排序：回到第一页重取 */
    public static void setSort(int newSort) {
        if (newSort == sort) {
            return;
        }
        sort = newSort;
        reload();
    }

    /**
     * 记录「界面这一帧画到了第几行」。
     *
     * <p>由 {@code WidgetListRecipe#createListEntryWidget} 调 —— malilib 只为<b>可见</b>的行
     * 建控件，所以这个下标天然就是「视口底部」。不读滚动条字段（那名字各版本不同，
     * 而且 remap 后完全变样）。</p>
     */
    public static void noteVisibleRow(int index) {
        if (index > lastVisibleRow) {
            lastVisibleRow = index;
        }
    }

    /**
     * 界面每帧报告「当前能完整显示几行」（按列表高度 / 行高现算）。
     *
     * <p>resize 之后行数会变，以最后一次报告为准。</p>
     */
    public static void setVisibleRows(int rows) {
        if (rows > 0 && rows != visibleRows) {
            visibleRows = rows;
        }
    }

    /** 只给单测 / 调试用：当前的可见行数 */
    public static int visibleRows() {
        return visibleRows;
    }

    /**
     * 点了一条「删除」。
     *
     * @return {@code true} = 这一次真的发出去了（界面该刷新文字）；
     *         {@code false} = 只是进入「再点一次」状态，或被限流挡下
     */
    public static boolean tapDelete(String recipeId) {
        long now = System.currentTimeMillis();
        RecipeBrowseModel.TapResult result =
                GATE.tap(recipeId, now, RecipeBrowseModel.CONFIRM_WINDOW_MS);
        revision++;
        if (result != RecipeBrowseModel.TapResult.CONFIRMED) {
            status = "再点一次确认删除（3 秒内）";
            return false;
        }
        if (now - lastDeleteAt < DELETE_INTERVAL_MS) {
            status = "删除太快了，等服务端回执（1 秒 1 次）";
            return false;
        }
        if (!PayloadChannels.usable(RecipeDeletePayload.TYPE)
                || !ClientPlayNetworking.canSend(RecipeDeletePayload.TYPE)) {
            phase = Phase.UNSUPPORTED;
            status = "对端不支持删除";
            revision++;
            return false;
        }
        lastDeleteAt = now;
        ClientPlayNetworking.send(new RecipeDeletePayload(List.of(recipeId)));
        status = "已发出删除请求…";
        revision++;
        return true;
    }

    /**
     * 界面每帧调一次：搜索防抖、两条超时判据、以及三种「该去要数据了」的兜底。
     *
     * <p>🔴 没有超时那一步，界面会永远停在「正在向服务端要配方本…」（真机就是这样：
     * 玩家在游戏里按热键打开，服务端 {@code openedMenu} 三道闸门没过，一个回包都不发）。
     * 「一直在转」和「功能没做」在玩家眼里没有区别。</p>
     */
    public static void tickProbe(String rawSearch) {
        pollSearch(rawSearch);
        long now = System.currentTimeMillis();
        if (phase == Phase.PROBING && now - helloSentAt > REPLY_TIMEOUT_MS) {
            phase = Phase.NO_REPLY;
            revision++;
            return;
        }
        if (pendingQueryAt != 0L && now - pendingQueryAt > REPLY_TIMEOUT_MS) {
            // 请求被静默丢掉：不提示的话，玩家只会看到「滚到底就不动了」
            pendingQueryAt = 0L;
            status = "服务端没有回应（点「重试」重问一次）";
            revision++;
        }
        ensureLoaded();
        prefetchIfNeeded();
    }

    /**
     * 兜底：索引说有内容、可一条都没到 ⇒ 自动再要一次（最多 {@link #MAX_PAGE_RETRIES} 次）。
     *
     * <p>🔴 这是给「接漏了线」这种故障兜底的：真机 0.4.9 就出现过标题写着「共 15 条」、
     * 底下一行都没有 —— 因为 {@link #onIndex} 收完索引没去要明细。补上之后仍然留着这一手：
     * 丢包、竞态、将来谁又改漏了，玩家看到的是「自己好了」，而不是一个空列表。</p>
     */
    public static void ensureLoaded() {
        if (phase != Phase.READY || total <= 0 || !entries.isEmpty() || pendingQueryAt != 0L) {
            return;
        }
        if (!search.isEmpty() && whitelist.isEmpty()) {
            return;
        }
        if (pageRetries >= MAX_PAGE_RETRIES) {
            if (status.isEmpty()) {
                status = "没能取到这一页（点「重试」再问一次）";
                revision++;
            }
            return;
        }
        pageRetries++;
        requestPage(nextPage);
    }

    /**
     * 预取下一段。
     *
     * <p>两种触发方式：浏览态等「画到最后 {@link RecipeBrowseModel#PREFETCH_ROWS} 行」；
     * 搜索态<b>不等滚动</b>，因为「按匹配度置顶」要求全部命中都到位（见类注释）。</p>
     */
    private static void prefetchIfNeeded() {
        if (phase != Phase.READY || pendingQueryAt != 0L || exhausted) {
            return;
        }
        if (!search.isEmpty()) {
            if (whitelist.isEmpty() || entries.size() >= total) {
                return;
            }
            if (nextPage >= MAX_SEARCH_PAGES) {
                if (status.isEmpty()) {
                    status = "命中太多了，只显示前 " + entries.size() + " 条（多打几个字）";
                    revision++;
                }
                exhausted = true;
                revision++;
                return;
            }
            requestPage(nextPage);
            return;
        }
        if (!RecipeBrowseModel.shouldPrefetch(lastVisibleRow, entries.size(), total, visibleRows)) {
            return;
        }
        if (entries.size() >= total) {
            exhausted = true;
            return;
        }
        requestPage(nextPage);
    }

    /**
     * 重试：把限流清零再来一次 hello。
     *
     * <p>界面上的「重试」按钮就是它：规则刚打开、或服务端刚换成新版时，不用关界面重进。</p>
     */
    public static void retry() {
        phase = Phase.IDLE;
        lastHelloAt = 0L;
        pendingQueryAt = 0L;
        status = "";
        DEBOUNCE.reset(search);
        hello();
        revision++;
    }

    /** 界面关掉时清掉待确认，避免「上次的确认残留到新列表」 */
    public static void clearPending() {
        GATE.clear();
        revision++;
    }

    /**
     * 这一条现在是不是「等着再点一次」的状态。
     *
     * <p>界面每次重建条目时都会问一遍 —— 所以「点了删除、文字变红、3 秒后自己复原」
     * 这条体验也不需要在控件里存状态（控件会被重建，状态存在 {@link #GATE} 里）。</p>
     */
    public static boolean isArmed(String recipeId) {
        return GATE.isArmed(recipeId, System.currentTimeMillis(),
                RecipeBrowseModel.CONFIRM_WINDOW_MS);
    }

    /** 断线清理：别留着上一个服务器的配方本 */
    public static void clear() {
        phase = Phase.IDLE;
        generation = -1;
        total = 0;
        used = 0;
        canDelete = false;
        outputs = List.of();
        entries = List.of();
        nextPage = 0;
        exhausted = false;
        lastVisibleRow = -1;
        sort = RecipeQueryPayload.SORT_OUTPUT;
        whitelist = List.of();
        search = "";
        status = "";
        GATE.clear();
        DEBOUNCE.reset("");
        lastHelloAt = 0L;
        lastQueryAt = 0L;
        pendingQueryAt = 0L;
        pageRetries = 0;
        lastDeleteAt = 0L;
        revision++;
    }

    // ------------------------------------------------------------------ 回包

    /**
     * 索引：产物清单 + 两个总数 + 权限 + 代次。
     *
     * <p>索引是「本子变了」的最强信号（代次在里面），所以这里顺手把池丢掉重建：
     * 新增了产物 ⇒ 搜索白名单要重算，否则玩家会「搜不到刚学会的那条」。</p>
     */
    public static void onIndex(RecipeIndexPayload payload) {
        phase = Phase.READY;
        generation = payload.generation();
        total = payload.total();
        used = payload.used();
        canDelete = payload.canDelete();
        outputs = payload.outputs() == null ? List.of() : List.copyOf(payload.outputs());
        entries = List.of();
        nextPage = 0;
        exhausted = false;
        lastVisibleRow = -1;
        pageRetries = 0;
        if (!search.isEmpty()) {
            whitelist = buildWhitelist(search);
        }
        revision++;
        LOGGER.info("[recipe] index: total={} used={} gen={} canDelete={} outputs={}",
                total, used, generation, canDelete, outputs.size());
        if (!search.isEmpty() && whitelist.isEmpty()) {
            total = 0;
            exhausted = true;
            status = "没有匹配的配方";
            revision++;
            return;
        }
        // 🔴 索引只管「有多少条、叫什么」，明细要单独要。这里不接一拳，界面就是空列表 ——
        // 真机 0.4.9 的表现：标题写着「共 15 条」，底下一行都没有。
        requestPage(0);
    }

    /**
     * 一页明细：第 0 页替换，其余追加。
     *
     * <p>代次比本地旧 ⇒ 丢弃（乱序到达的旧包，用了会把界面拉回上一版）。
     * 代次比本地新 ⇒ 说明中间漏了一次广播，<b>认同这一页但把 generation 抬上去</b> ——
     * 服务端回的这页就是最新数据，没必要为了「等广播」再跑一个来回。</p>
     */
    public static void onPage(RecipePagePayload payload) {
        phase = Phase.READY;
        pendingQueryAt = 0L;
        if (payload.generation() < generation) {
            return;
        }
        generation = payload.generation();
        total = payload.total();
        List<RecipePagePayload.Entry> incoming =
                payload.entries() == null ? List.of() : List.copyOf(payload.entries());
        int index = payload.page();
        entries = index <= 0 ? incoming : appendUnique(entries, incoming);
        nextPage = index + 1;
        if (incoming.isEmpty() || entries.size() >= total) {
            exhausted = true;
        }
        if (!incoming.isEmpty()) {
            // 真的拿到内容了才算「这一页通了」；空页不清零，否则坏服务端能让补要逻辑转成死循环
            pageRetries = 0;
        }
        if (!search.isEmpty()) {
            entries = sortedByRank(entries);
        }
        revision++;
        LOGGER.info("[recipe] page {}: {} 条（累计 {}/{} gen={} search=\"{}\"）",
                index, incoming.size(), entries.size(), total, generation, search);
    }

    /** 删除回执：更新计数，并按代次重取（池里那条已经作废，不能只从本地删掉就完事） */
    public static void onDeleteResult(RecipeDeleteResultPayload payload) {
        generation = payload.generation();
        used = payload.remaining();
        canDelete = payload.canDelete();
        GATE.clear();
        List<String> removed = payload.removed() == null ? List.of() : payload.removed();
        List<RecipeDeleteResultPayload.Failure> failed =
                payload.failed() == null ? List.of() : payload.failed();
        LOGGER.info("[recipe] delete result: status={} removed={} failed={}",
                payload.status(), removed.size(), failed.size());
        status = switch (payload.status()) {
            case RecipeDeleteResultPayload.OK -> "已删除 " + removed.size() + " 条";
            case RecipeDeleteResultPayload.PARTIAL -> "删掉 " + removed.size()
                    + " 条，" + failed.size() + " 条没删成";
            case RecipeDeleteResultPayload.NOTHING -> "没删成：" + failureText(failed);
            case RecipeDeleteResultPayload.TOO_MANY -> "一次太多了（服务端上限 "
                    + RecipeDeletePayload.MAX_IDS + " 条）";
            case RecipeDeleteResultPayload.THROTTLED -> "删太快，服务端挡下了（1 秒 1 次）";
            case RecipeDeleteResultPayload.RULE_OFF -> "服务端的虚空仓库规则已关闭";
            default -> "删除结果未知（status=" + payload.status() + "）";
        };
        revision++;
        if (removed.isEmpty()) {
            return;
        }
        // 删成功才重取：失败时列表内容没变，重建只会把玩家的滚动位置打回顶部
        reload();
    }

    /**
     * 变更广播：别人学了一条 / 删了一条。
     *
     * <p>只做一件事 —— 把 generation 抬上去并重取。不弹提示（学配方是高频动作，
     * 全服刷屏没有意义），也不清空列表（重取只要 50ms，清空反而闪一下）。</p>
     */
    public static void onChanged(RecipeChangedPayload payload) {
        if (payload.generation() <= generation) {
            return;
        }
        generation = payload.generation();
        revision++;
        // 界面关着就不重取：下次 open() 会自然拿到新的索引
        if (RecipeBrowseGui.isOpen()) {
            reload();
        }
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 按匹配度给条目排序（搜索态专用）。
     *
     * <p>排序键：匹配度 → 显示名 → recipeId。第三级是必要的：同名产物（不同 mod 各加一份
     * 「铁锭」）在只有前两级时会「顺序随机」，两次请求看到的顺序不同，玩家会以为列表在跳。</p>
     */
    private static List<RecipePagePayload.Entry> sortedByRank(List<RecipePagePayload.Entry> src) {
        List<RecipePagePayload.Entry> out = new ArrayList<>(src);
        Comparator<RecipePagePayload.Entry> order = Comparator
                .comparingInt((RecipePagePayload.Entry e) -> RecipeBrowseModel.matchRank(
                        search, SearchCatalogResolver.displayNameOf(e.output()), e.output(),
                        SearchCatalogResolver.pinyinFullOf(e.output()),
                        SearchCatalogResolver.pinyinInitialsOf(e.output())))
                .thenComparing(e -> SearchCatalogResolver.nameOf(e.output()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(RecipePagePayload.Entry::recipeId);
        out.sort(order);
        return List.copyOf(out);
    }

    /** 追加并去重（按 recipeId）：两次请求之间本子变了的话，服务端的分页窗口会滑动 */
    private static List<RecipePagePayload.Entry> appendUnique(List<RecipePagePayload.Entry> base,
                                                             List<RecipePagePayload.Entry> add) {
        if (add.isEmpty()) {
            return base;
        }
        if (base.isEmpty()) {
            return add;
        }
        List<RecipePagePayload.Entry> out = new ArrayList<>(base.size() + add.size());
        out.addAll(base);
        Set<String> seen = new HashSet<>();
        for (RecipePagePayload.Entry entry : base) {
            seen.add(entry.recipeId());
        }
        for (RecipePagePayload.Entry entry : add) {
            if (seen.add(entry.recipeId())) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * 把搜索词翻成产物 id 白名单（发给服务端的唯一搜索凭据）。
     *
     * <p>顺序有意义：先按匹配度、再按名字 —— 服务端只按 id 字典序返回，所以「置顶」
     * 最终由 {@link #sortedByRank} 在客户端落实；这里排一遍是为了让白名单本身可读，
     * 也方便日志里一眼看出「命中的是不是想要的那几个」。</p>
     */
    private static List<String> buildWhitelist(String query) {
        String q = RecipeBrowseModel.normalizeQuery(query);
        if (q.isEmpty() || outputs.isEmpty()) {
            return List.of();
        }
        List<String> matched = new ArrayList<>();
        for (String id : outputs) {
            if (RecipeBrowseModel.matches(q, SearchCatalogResolver.displayNameOf(id), id,
                    SearchCatalogResolver.pinyinFullOf(id), SearchCatalogResolver.pinyinInitialsOf(id))) {
                matched.add(id);
            }
        }
        matched.sort(Comparator
                .comparingInt((String id) -> RecipeBrowseModel.matchRank(
                        q, SearchCatalogResolver.displayNameOf(id), id,
                        SearchCatalogResolver.pinyinFullOf(id), SearchCatalogResolver.pinyinInitialsOf(id)))
                .thenComparing(id -> SearchCatalogResolver.nameOf(id), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(Comparator.naturalOrder()));
        return List.copyOf(matched);
    }

    private static String failureText(List<RecipeDeleteResultPayload.Failure> failed) {
        if (failed.isEmpty()) {
            return "服务端没给原因";
        }
        RecipeDeleteResultPayload.Failure first = failed.get(0);
        String reason = switch (first.reason()) {
            case RecipeDeleteResultPayload.NOT_FOUND -> "这条配方已经不在本子里了";
            case RecipeDeleteResultPayload.BAD_ID -> "配方 id 不合法";
            default -> "原因码 " + first.reason();
        };
        return failed.size() == 1 ? reason : reason + "（另外 " + (failed.size() - 1) + " 条同样失败）";
    }

    /** 给界面用：把 payload 的原料摊成「名字 ×数量」的字符串（名字解析在界面那层） */
    static List<String> partIds(RecipePagePayload.Entry entry) {
        List<String> ids = new ArrayList<>();
        if (entry == null || entry.parts() == null) {
            return ids;
        }
        for (RecipePagePayload.Part part : entry.parts()) {
            if (part != null && part.candidates() != null && !part.candidates().isEmpty()) {
                ids.add(part.candidates().get(0));
            }
        }
        return ids;
    }
}
