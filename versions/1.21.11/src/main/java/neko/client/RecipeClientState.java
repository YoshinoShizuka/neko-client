package neko.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import neko.client.RecipeBrowseModel.Phase;
import neko.voidwarehouse.recipe.RecipeChangedPayload;
import neko.voidwarehouse.recipe.RecipeDeletePayload;
import neko.voidwarehouse.recipe.RecipeDeleteResultPayload;
import neko.voidwarehouse.recipe.RecipeHelloPayload;
import neko.voidwarehouse.recipe.RecipeIndexPayload;
import neko.voidwarehouse.recipe.RecipePagePayload;
import neko.voidwarehouse.recipe.RecipeQueryPayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方本（服务端那本全服共用的）在客户端的状态机：探测能力、要索引、要某一页、删条目。
 *
 * <h2>一句话说清它管什么</h2>
 * <p>服务端只给「索引 / 分页 / 删除 / 变更广播」四件事（见
 * {@code carpet-Neko-Addition} 的 {@code docs/方案-配方本浏览与删除（服务端）.md}）。
 * 这里负责把它们接起来，并把结果摊平成界面能直接读的字段 —— <b>不做任何文字匹配</b>，
 * 名字筛选在 {@link RecipeBrowseGui} 那一层（只有客户端有中文语言表）。</p>
 *
 * <h2>三条硬性做法（都不是随便定的）</h2>
 * <ol>
 *   <li><b>发之前过两道门禁</b>：{@link PayloadChannels#usable} 查归属、
 *       {@link ClientPlayNetworking#canSend} 查对端。撞名的若是别的类，那一发会在编码阶段
 *       炸在 netty 线程上，{@code try/catch} 抓不到，玩家只会看到断线。</li>
 *   <li><b>回包只改字段 + 自增 {@link #revision}</b>，绝不在这里碰控件。
 *       界面每帧比对 revision，变了就重建 —— 这样不需要关心回调在哪个线程。</li>
 *   <li><b>删除必须点两次</b>（{@link RecipeBrowseModel.ConfirmGate}）。服务端是全局真删、
 *       没有撤销，误删的代价由全服承担。</li>
 * </ol>
 *
 * <h2>为什么 generation 是缓存键</h2>
 * <p>别人学了一条配方、或管理员删了一条，服务端都会把 {@code generation} 加一。
 * 客户端手里那页明细此刻就已经过期了 —— 与其做增量补丁（服务端也没打算发），
 * 不如丢掉重取。见 {@link #onChanged}。</p>
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
     * 「索引说有条目、可一页明细迟迟没到」时，最多自动补要几次。
     *
     * <p>2 次够覆盖一次丢包；再多就成了每 2 秒一包地刷服务端。</p>
     */
    private static final int MAX_PAGE_RETRIES = 2;

    private static int generation = -1;
    private static int total;
    private static int used;
    private static boolean canDelete;
    private static List<String> outputs = List.of();
    private static List<RecipePagePayload.Entry> entries = List.of();
    private static int page;
    private static int pageSize = RecipeBrowseModel.PAGE_SIZE;
    private static int sort = RecipeQueryPayload.SORT_OUTPUT;
    private static List<String> whitelist = List.of();
    private static String status = "";
    private static int revision;

    private static final RecipeBrowseModel.ConfirmGate GATE = new RecipeBrowseModel.ConfirmGate();

    private static long lastHelloAt;
    /** 最近一次 hello 发出的时刻，用于 {@link #tickProbe} 判超时 */
    private static long helloSentAt;
    private static long lastQueryAt;
    /**
     * 最近一次 query 发出的时刻；{@code 0} = 没有待决的翻页请求。
     *
     * <p>存在的理由和 {@link #helloSentAt} 一样：服务端在闸门没过时是静默丢包的，
     * 没有这个字段，玩家点「下一页」只会看到页码不动、列表不换 —— 又是一种「没反应」。</p>
     */
    private static long pendingQueryAt;
    /** 本次索引之后已经自动补要过几次第一页（见 {@link #ensurePageLoaded()}） */
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

    public static int page() {
        return page;
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

    public static String status() {
        return status;
    }

    /** 每收到一个回包自增；界面靠它决定要不要重建控件 */
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

    /** 要某一页（50ms 限流，页码就地夹取） */
    public static void requestPage(int target) {
        long now = System.currentTimeMillis();
        if (now - lastQueryAt < QUERY_INTERVAL_MS) {
            return;
        }
        int page0 = RecipeBrowseModel.clampPage(target, total, pageSize);
        if (!PayloadChannels.usable(RecipeQueryPayload.TYPE)
                || !ClientPlayNetworking.canSend(RecipeQueryPayload.TYPE)) {
            phase = Phase.UNSUPPORTED;
            revision++;
            return;
        }
        lastQueryAt = now;
        pendingQueryAt = now;
        // 🔴 page 不在这里改：服务端没回包时页面会显示「第 2 页」而列表还是第 1 页的内容，
        // 那是最难查的一种谎话。页码一律以 onPage 收到的为准。
        ClientPlayNetworking.send(new RecipeQueryPayload(page0, pageSize, sort, whitelist));
        revision++;
    }

    /** 换排序：回到第一页重取 */
    public static void setSort(int newSort) {
        if (newSort == sort) {
            return;
        }
        sort = newSort;
        requestPage(0);
    }

    /**
     * 设置产物白名单（名字筛选的结果）。
     *
     * <p>空列表 = 不过滤（与服务端「空数组 = 不过滤」同义）。换过滤集必须回第一页 ——
     * 否则会停在「第 7 页」而结果只有 1 页。</p>
     */
    public static void setWhitelist(List<String> ids) {
        List<String> next = ids == null ? List.of() : List.copyOf(ids);
        if (next.equals(whitelist)) {
            return;
        }
        whitelist = next;
        requestPage(0);
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
     * 界面每帧调一次：两条超时判据（hello 与 query）。
     *
     * <p>🔴 没有这一步，界面会永远停在「正在向服务端要配方本…」（真机就是这样：
     * 玩家在游戏里按热键打开，服务端 {@code openedMenu} 三道闸门没过，一个回包都不发）。
     * 「一直在转」和「功能没做」在玩家眼里没有区别。</p>
     */
    public static void tickProbe() {
        long now = System.currentTimeMillis();
        if (phase == Phase.PROBING && now - helloSentAt > REPLY_TIMEOUT_MS) {
            phase = Phase.NO_REPLY;
            revision++;
            return;
        }
        if (pendingQueryAt != 0L && now - pendingQueryAt > REPLY_TIMEOUT_MS) {
            // 翻页被静默丢掉：页码不会再动，得有人告诉玩家为什么
            pendingQueryAt = 0L;
            status = "服务端没有回应（点「重试」重问一次）";
            revision++;
        }
        ensurePageLoaded();
    }

    /**
     * 兜底：索引说有内容、可第一页一行都没有 ⇒ 自动再要一次（最多 {@link #MAX_PAGE_RETRIES} 次）。
     *
     * <p>🔴 这是给「接漏了线」这种故障兜底的：真机 0.4.9 就出现过标题写着「共 15 条·第 1 / 2 页」、
     * 底下一行都没有 —— 因为 {@link #onIndex} 收完索引没去要明细。补上之后仍然留着这一手：
     * 丢包、竞态、将来谁又改漏了，玩家看到的是「自己好了」，而不是一个空列表。</p>
     *
     * <p>只补第一页（{@code page == 0}）：翻页翻空有可能是真的没内容，那一补就会打转。</p>
     */
    public static void ensurePageLoaded() {
        if (phase != Phase.READY || total <= 0 || page != 0
                || !entries.isEmpty() || pendingQueryAt != 0L) {
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
        requestPage(page);
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
        hello();
        revision++;
    }

    /** 界面关掉 / 换页时清掉待确认，避免「上次的确认残留到新列表」 */
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
        page = 0;
        sort = RecipeQueryPayload.SORT_OUTPUT;
        whitelist = List.of();
        status = "";
        GATE.clear();
        lastHelloAt = 0L;
        lastQueryAt = 0L;
        pendingQueryAt = 0L;
        pageRetries = 0;
        lastDeleteAt = 0L;
        revision++;
    }

    // ------------------------------------------------------------------ 回包

    /** 索引：产物清单 + 两个总数 + 权限 + 代次。只更新状态，请求页码由界面决定 */
    public static void onIndex(RecipeIndexPayload payload) {
        phase = Phase.READY;
        generation = payload.generation();
        total = payload.total();
        used = payload.used();
        canDelete = payload.canDelete();
        outputs = payload.outputs() == null ? List.of() : List.copyOf(payload.outputs());
        pageRetries = 0;
        revision++;
        // 🔴 索引只管「有多少条、叫什么」，一页明细要单独要。这里不接一拳，界面就是空列表 ——
        // 真机 0.4.9 的表现：标题写着「共 15 条 / 第 1 / 2 页」，底下一行都没有。
        requestPage(page);
    }

    /**
     * 一页明细。
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
        page = payload.page();
        total = payload.total();
        entries = payload.entries() == null ? List.of() : List.copyOf(payload.entries());
        if (!entries.isEmpty()) {
            // 真的拿到内容了才算「这一页通了」；空页不清零，否则坏服务端能让补要逻辑转成死循环
            pageRetries = 0;
        }
        revision++;
    }

    /** 删除回执：逐条处理，失败的那条必须留在界面上（不能乐观删除后不回滚） */
    public static void onDeleteResult(RecipeDeleteResultPayload payload) {
        generation = payload.generation();
        used = payload.remaining();
        canDelete = payload.canDelete();
        GATE.clear();
        List<String> removed = payload.removed() == null ? List.of() : payload.removed();
        List<RecipeDeleteResultPayload.Failure> failed =
                payload.failed() == null ? List.of() : payload.failed();
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
    }

    /**
     * 变更广播：别人学了一条 / 删了一条。
     *
     * <p>只做一件事 —— 把 generation 抬上去并重取当前页。不弹提示（学配方是高频动作，
     * 全服刷屏没有意义），也不清空列表（重取只要 50ms，清空反而闪一下）。</p>
     */
    public static void onChanged(RecipeChangedPayload payload) {
        if (payload.generation() <= generation) {
            return;
        }
        generation = payload.generation();
        revision++;
        // 界面开着才重取；没开就等下次 open() 自然拿新的
        if (RecipeBrowseGui.isOpen()) {
            requestPage(page);
        }
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
