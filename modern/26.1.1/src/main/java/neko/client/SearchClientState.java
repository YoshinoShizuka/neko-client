package neko.client;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.components.EditBox;
import neko.voidwarehouse.fill.FillRequestPayload;
import neko.voidwarehouse.search.SearchApplyPayload;
import neko.voidwarehouse.search.SearchHelloPayload;

import java.util.List;

/**
 * 搜索框的客户端状态机：一个屏幕一份（静态单例 —— 同一时刻只有一个屏幕在前台）。
 *
 * <p>（public：mixin 在 {@code .search.mixin} 子包里，跨包访问需要。）</p>
 *
 * <p>生命周期由 mixin（{@code MixinAbstractContainerScreen}）驱动：
 * {@code init} 时 {@link #bind(EditBox)}，每 tick 调 {@link #tick()}，
 * {@code removed} 时 {@link #clear()}。</p>
 *
 * <h2>数据流</h2>
 * <pre>
 *   init ──hello──▶ 服务端（判断是虚空仓库才回） ──catalog──▶ 建索引 + 显示搜索框
 *   输入 ──▶ 本地匹配 ──pendingQuery──▶ tick 里带尾随防抖 ──apply──▶ 服务端过滤出页
 *   清空输入 ──apply(空)──▶ 服务端恢复完整列表
 * </pre>
 *
 * <h2>两个防抖各管一头</h2>
 * <ul>
 *   <li><b>hello（打开界面 / 请求刷新目录）500ms 一次</b>：界面打开后服务端变更是
 *       可接受的滞后（评估已定），用户点一下搜索框就能强制刷新；</li>
 *   <li><b>apply 尾随防抖 150ms</b>：打字快的时候前面的查询直接被覆盖，
 *       但<b>最后一个查询一定会在 150ms 内发出去</b> —— 这是「首键即滤、尾键不丢」的
 *       关键，比纯节流多出来的那个尾随保证。</li>
 * </ul>
 *
 * <h2>优雅降级</h2>
 * <p>{@code canSend} 为 false（旧服务端没这条通道）⇒ hello 发不出去 ⇒
 * 目录永远不来 ⇒ 搜索框永远 {@code setVisible(false)}，功能整个不存在，
 * 界面上没有任何残迹。</p>
 *
 * <p>还有第二层门禁：{@link PayloadChannels#usable} 为 false（这个通道 id 名下登记的是
 * <b>别人的</b> codec）⇒ 一个字节都不发。那种情况下发送会在编码阶段抛错，而异常落在
 * netty 线程上 catch 不到，只会表现为<b>断线</b> —— 所以这里宁可直接放弃功能。</p>
 */
public final class SearchClientState {

    /** apply 尾随防抖间隔（毫秒） */
    private static final long APPLY_DEBOUNCE_MS = 150L;
    /** hello 最短间隔（毫秒） */
    private static final long HELLO_INTERVAL_MS = 500L;

    private static EditBox box;
    /** 当前绑定的容器界面（{@link SearchScreenAccess}）；热键切换开关时用它就地刷新 */
    private static SearchScreenAccess screen;
    private static List<SearchIndex.Entry> index = List.of();
    /** 待发送的查询（{@code null} = 没有待发的） */
    private static String pendingQuery;
    private static long lastApplySentAt;
    private static long lastHelloSentAt;

    /**
     * 当前屏幕是不是<b>虚空仓库</b>。
     *
     * <p>判据只有一个：<b>这个菜单收到过服务端的 catalog</b> —— 服务端只在
     * {@code player.containerMenu instanceof VoidWarehouseMenu} 时才回 catalog，
     * 所以「收到过」就是「服务端认过这个界面」。普通大箱子永远不会置 true。</p>
     *
     * <p>为什么不能只看 {@code !index.isEmpty()}：仓库里一件物品都没有（或全是
     * 客户端不认识的 id）时目录是空表，那和「这不是仓库」用 index 分不开。</p>
     */
    private static boolean warehouse;

    private SearchClientState() {
    }

    /**
     * 屏幕初始化（大箱子类）时绑定新的搜索框，并要一份目录。
     *
     * <p><b>两个功能都关着就别握手了</b>：hello/catalog 是搜索与取满共用的前置，
     * 都关了还发，等于把功能关了却照样留一条流量 —— 那就不是「开关」了。</p>
     */
    public static void bind(EditBox searchBox, SearchScreenAccess host) {
        box = searchBox;
        screen = host;
        index = List.of();
        pendingQuery = null;
        warehouse = false;
        // 每一次 bind 都会把「服务端认过这个界面」清零，必须留痕：玩家反复开关界面时
        // 若 hello 被 500ms 节流挡掉，warehouse 就会一直停在 false，取满静默失效。
        SearchConfig.trace("界面绑定：warehouse 清零，功能开关={}",
                SearchConfig.anyFeatureEnabled() ? "至少一个开着" : "全关");
        if (SearchConfig.anyFeatureEnabled()) {
            sendHello();
        }
    }

    /** 屏幕关闭：状态全部作废。服务端那边的过滤跟着菜单一起消失，无需任何清理包 */
    public static void clear() {
        box = null;
        screen = null;
        index = List.of();
        pendingQuery = null;
        warehouse = false;
        SearchConfig.trace("界面关闭：warehouse 清零");
    }

    /**
     * 服务端回目录了：建索引、按开关亮出搜索框，把已输入的查询立刻补一次匹配。
     *
     * <p>🔴 <b>{@code warehouse} 与「有没有搜索框」必须分开</b>：搜索框关掉、
     * 但取满手势还开着的时候，它照样要知道「服务端认过这个界面」，
     * 而那个判定唯一的来源就是这条 catalog。以前这里 {@code box == null} 直接
     * return，等于把取满也一起判死 —— 开关拆分之后必须把置位提到前面。</p>
     */
    public static void onCatalog(List<String> itemIds) {
        warehouse = true;
        SearchConfig.trace("收到服务端目录：{} 条 ⇒ warehouse=true（取满手势的门禁已放行）",
                itemIds == null ? -1 : itemIds.size());
        index = SearchIndex.build(itemIds, SearchCatalogResolver::displayNameOf);
        if (box == null) {
            return;
        }
        box.setVisible(SearchConfig.isSearchBoxEnabled());
        if (SearchConfig.isSearchBoxEnabled() && !box.getValue().isBlank()) {
            requestApply(box.getValue());
        }
    }

    /**
     * 让「当前开着的容器界面」按最新配置刷新一次（热键切换后调用）。
     *
     * <p>没有界面开着就什么都不做 —— 下次 {@code init} 自然按新配置走。</p>
     */
    public static void applyFeatureStateToCurrentScreen() {
        if (screen != null) {
            screen.vwsearch$applyFeatureState();
        }
    }

    /** 当前界面是不是虚空仓库（收到过 catalog）；取满手势的门禁之一 */
    public static boolean isWarehouse() {
        return warehouse;
    }

    /**
     * 搜索框此刻是不是聚焦着（玩家正在里面打字）。
     *
     * <p>malilib 的热键是 {@code Context.ANY} + {@code cancel=false}：界面里也响应、
     * 但不吞按键。所以「正在打字」时热键回调必须<b>自己让路</b>，否则打一个
     * {@code h} 会把搜索框关掉。判据直接用输入框自己的焦点状态 —— 它才是
     * 唯一真实的来源（界面侧没有第二份焦点副本）。</p>
     */
    public static boolean isSearchFocused() {
        return box != null && box.isFocused();
    }

    /** 搜索框内容变化（responder）：只记下待发查询，真正发送在 tick 里带尾随防抖 */
    public static void onQueryChanged(String query) {
        // 开关关着时一个字都不往服务端发（清空由 exitSearch 负责，不走这条）
        if (!SearchConfig.isSearchBoxEnabled()) {
            return;
        }
        requestApply(query);
    }

    /**
     * 搜索功能被关掉时收尾：<b>撤掉服务端挂着的过滤</b>。
     *
     * <p>为什么必须显式发一条空集合，而不是让搜索框「清空输入」自动触发：
     * 关开关的顺序是「先改配置、再刷界面」，刷界面时 {@link #onQueryChanged}
     * 已经因为开关关着而短路了 —— 结果就是界面看着没有搜索框，仓库却还卡在
     * 上一次的过滤结果里，玩家连撤都撤不掉。空集合在服务端就是「清除」
     * （{@code SearchNetwork#handleApply} 的契约），一条包搞定。</p>
     */
    public static void exitSearch() {
        pendingQuery = null;
        if (warehouse) {
            sendApply(List.of());
        }
    }

    /** 每 tick 推进一次尾随防抖（mixin 的 containerTick TAIL 调用） */
    public static void tick() {
        if (pendingQuery == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastApplySentAt < APPLY_DEBOUNCE_MS) {
            return;
        }
        String query = pendingQuery;
        pendingQuery = null;
        sendApply(SearchIndex.match(index, query));
        lastApplySentAt = now;
    }

    /** 点了搜索框 = 用户在等结果，顺手刷新一次目录（服务端限流 100ms，这里 500ms 够宽） */
    public static void onSearchBoxClicked() {
        sendHello();
    }

    private static void requestApply(String query) {
        pendingQuery = query;
    }

    private static void sendHello() {
        long now = System.currentTimeMillis();
        if (now - lastHelloSentAt < HELLO_INTERVAL_MS) {
            SearchConfig.trace("请求目录：被 {}ms 节流挡下", HELLO_INTERVAL_MS);
            return;
        }
        lastHelloSentAt = now;
        // 旧服务端没这条通道：canSend 为 false，发不出去，搜索框永远不亮 —— 这就是降级。
        // 🔴 还要先过通道归属门禁：撞名的若是别的类，这一发会在编码阶段炸在 netty 线程上，
        // catch 不到，只会表现为断线。宁可搜索框不出现。
        if (!PayloadChannels.usable(SearchHelloPayload.TYPE)) {
            SearchConfig.trace("请求目录：通道归属门禁没过（不发）");
            return;
        }
        if (!ClientPlayNetworking.canSend(SearchHelloPayload.TYPE)) {
            SearchConfig.trace("请求目录：对端不支持 search_hello（canSend=false，不发）");
            return;
        }
        ClientPlayNetworking.send(SearchHelloPayload.INSTANCE);
        SearchConfig.trace("请求目录：search_hello 已发出（等 catalog）");
    }

    /**
     * 取满手势发现目录还没到时，绕过节流立刻再要一次目录。
     *
     * <p>只在「玩家已经按了组合键」这种明确意图下调用，不会自己刷包；服务端
     * 本来就有 100ms 的 hello 限流，重复要一份目录没有副作用。</p>
     */
    public static void refreshCatalog() {
        lastHelloSentAt = 0L;
        sendHello();
    }

    private static void sendApply(List<String> matchedIds) {
        if (!PayloadChannels.usable(SearchApplyPayload.TYPE)) {
            return;
        }
        if (ClientPlayNetworking.canSend(SearchApplyPayload.TYPE)) {
            ClientPlayNetworking.send(new SearchApplyPayload(matchedIds));
        }
    }

    /**
     * 取满手势：把「这一格」的填充请求发给服务端 —— 服务端会把这格的物品
     * <b>取出到背包放不下为止</b>（常规模式纯散货、潜影盒模式给满盒，都由服务端按
     * 那个玩家当前的模式与真实背包现算）。
     *
     * <p><b>只发 slotId</b>：物品是什么、能装多少，全由服务端从自己的数据算，
     * 客户端报的数字一概不作数。</p>
     *
     * @return {@code true} = 请求已发出（调用方<b>必须取消原版点击</b>，否则会顺手再取 1 个）；
     *         {@code false} = 对端没这条通道（旧服务端 / 纯原版服），调用方<b>什么都别做</b>，
     *         让这次点击退回原版语义
     */
    public static boolean sendFill(int slotId) {
        // 门禁一：通道归属（撞名的是别的类时，这一发会在 netty 线程上炸掉连接）
        // 门禁二：对端到底收不收（旧服务端 / 纯原版服 —— 这条是正常的「不支持」）
        // 两条都过不去就返回 false，调用方不取消原版点击 ⇒ 玩家看到的还是「取 1 个」，
        // 没有残迹也没有报错。
        // 结果日志走 audit（独立预算）：0.4.5 真机上判据行把「取满发包成功」挤掉过，
        // 于是「明明取到货了、日志却看不到发包」——分级不分开，关键结果随时会消失。
        if (!PayloadChannels.usable(FillRequestPayload.TYPE)) {
            SearchConfig.audit("取满发包失败：fill_request 通道归属门禁没过");
            return false;
        }
        if (!ClientPlayNetworking.canSend(FillRequestPayload.TYPE)) {
            SearchConfig.audit("取满发包失败：对端不收 fill_request（canSend=false）");
            return false;
        }
        ClientPlayNetworking.send(new FillRequestPayload(slotId));
        SearchConfig.audit("取满发包成功：slotId={}", slotId);
        return true;
    }
}
