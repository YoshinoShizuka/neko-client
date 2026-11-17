package neko.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
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

    /** 能力探测阶段 */
    public enum Phase {
        /** 还什么都没问（没打开过界面 / 刚断线） */
        IDLE,
        /** 发过 hello，还没等到 index */
        PROBING,
        /** 拿到过 index，可以浏览 */
        READY,
        /** 对端没有这条通道（旧服务端 / 纯原版服）⇒ 入口不该出现 */
        UNSUPPORTED
    }

    private static final long HELLO_INTERVAL_MS = 100L;
    private static final long QUERY_INTERVAL_MS = 50L;
    private static final long DELETE_INTERVAL_MS = 1000L;

    private static Phase phase = Phase.IDLE;
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
    private static long lastQueryAt;
    private static long lastDeleteAt;

    private RecipeClientState() {
    }

    // ------------------------------------------------------------------ 读侧

    public static Phase phase() {
        return phase;
    }

    /** 界面该不该开：对端不支持时直接不给入口，而不是开一个永远空着的界面 */
    public static boolean supported() {
        return phase == Phase.READY || phase == Phase.PROBING;
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
     * <p>不做「不支持就报错」——{@link Phase#UNSUPPORTED} 的语义是「入口都不该出现」，
     * 而这里已经是界面打开了，说明调用方判断过 {@link #supported()}。</p>
     */
    public static void open() {
        status = "";
        GATE.clear();
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
        page = page0;
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
        revision++;
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
        if (payload.generation() < generation) {
            return;
        }
        generation = payload.generation();
        page = payload.page();
        total = payload.total();
        entries = payload.entries() == null ? List.of() : List.copyOf(payload.entries());
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
