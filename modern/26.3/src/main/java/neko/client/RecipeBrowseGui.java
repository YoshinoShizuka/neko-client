package neko.client;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import neko.voidwarehouse.recipe.RecipePagePayload;
import neko.voidwarehouse.recipe.RecipeQueryPayload;

import java.util.List;

/**
 * 配方本界面：一页 8 条配方，逐条可删。
 *
 * <h2>长什么样</h2>
 * <pre>
 * 已用 137 / 2048 · 第 1 / 18 页 · 共 137 条
 * 上一页的结果…
 * ─────────────────────────────────────────
 * 铁剑 ×1                     原料：铁锭 ×2、木棍 ×1     [删除]
 * 铁镐 ×1                     原料：铁锭 ×3、木棍 ×2     [删除]
 * …
 * ─────────────────────────────────────────
 * [上一页] [下一页] [排序：按名字]                        [关闭]
 * </pre>
 *
 * <h2>为什么继承 {@code GuiListBase} 而不是自己画</h2>
 * <p>滚动、键盘导航、条目命中的容差、hover 提示都是 malilib 已经写好的（Litematica 的
 * 材料列表同款）。自己画一遍只会多出三类 bug：滚轮方向、点偏 1px、翻页后滚动条不回位。
 * 这里只提供「列表从哪来」和「一行长什么样」。</p>
 *
 * <h2>🔴 回包之后怎么刷新</h2>
 * <p>{@link RecipeClientState} 的回包回调只改字段 + 自增 {@code revision}，
 * <b>不碰任何控件</b>。界面在每帧的 {@link #drawContents} 里比对 revision，变了才
 * {@code reCreateListWidget()} —— 这样既不关心回调在哪个线程，也不会在渲染途中被
 * 另一个线程改列表（那种崩溃的堆栈一眼看不出是模组的锅）。</p>
 *
 * <h2>🔴 不再判「有没有开着仓库界面」（0.4.9 更正）</h2>
 * <p>第一版在这里判过 {@code containerMenu instanceof InventoryMenu}：不是容器界面就<b>连 hello 都不发</b>，
 * 理由是「服务端只认开着的仓库界面」。那个前提在服务端 1.0.17 就取消了
 * （{@code RecipeNetwork#dataOf} 只判规则与数据能不能取到），于是这条判断只剩一个效果：
 * 玩家在世界上按热键时，把一个本来会成功的请求挡在客户端，界面永远空着。</p>
 * <p>现在只做一件事：<b>发 hello，等 2 秒</b>。没回音才说话（{@link RecipeBrowseModel#hintLines}）。</p>
 *
 * <h2>这一版没有文本搜索</h2>
 * <p>服务端分页 ⇒ 客户端过滤只能筛当前页 8 条，搜「铁锭」而它在第 9 页时会被判「没有」。
 * 真搜索要把匹配出的产物 id 白名单发给服务端重取（{@code recipe_query.outputs} 已经支持），
 * 那需要输入框 + 跨版本键盘分发，留到下一版。当前按产物名排序 + 翻页，实测存档
 * （learned 十几条到一两百条）完全够用。</p>
 */
public class RecipeBrowseGui extends GuiListBase<RecipePagePayload.Entry, WidgetListRecipeEntry, WidgetListRecipe> {

    private static final int LIST_X = 10;
    /** 列表默认顶边；有提示行时整体下移（见 {@link #hintSpace()}） */
    private static final int LIST_Y = 36;
    private static final int HINT_LINE_HEIGHT = 11;

    private static RecipeBrowseGui current;

    /** 上次看到的 revision：变了才重建列表 */
    private int seenRevision = -1;

    /** 当前要画的提示行（空 = 正常浏览，不占版面） */
    private List<String> hints = List.of();

    /** 本次打开界面是否已经问过服务端（{@code initGui} 会被 resize 再调一次，别重复发 hello） */
    private boolean requestedOnOpen;

    public RecipeBrowseGui() {
        super(LIST_X, LIST_Y);
        this.setTitle("配方本");
    }

    /** 供 {@link RecipeClientState#onChanged} 判断「有没有界面要刷新」 */
    public static boolean isOpen() {
        return current != null;
    }

    @Override
    protected WidgetListRecipe createListWidget(int listX, int listY) {
        return new WidgetListRecipe(listX, listY, this.getBrowserWidth(), this.getBrowserHeight());
    }

    @Override
    protected int getBrowserWidth() {
        return Math.max(160, this.getScreenWidth() - 20);
    }

    @Override
    protected int getBrowserHeight() {
        return Math.max(72, this.getScreenHeight() - 100 - this.hintSpace());
    }

    @Override
    public void initGui() {
        current = this;
        super.initGui();

        // 只问一次：resize 也会走到这里，重复发 hello 没意义（服务端还有 100ms 限流）
        if (!this.requestedOnOpen) {
            this.requestedOnOpen = true;
            RecipeClientState.open();
        }

        int y = this.getScreenHeight() - 26;
        int x = 10;
        int btnHeight = 20;

        // 上一页
        this.addButton(new ButtonGeneric(x, y, 56, btnHeight, "上一页", "上一页（PageUp）"),
                (base, mouseButton) -> RecipeClientState.requestPage(RecipeClientState.page() - 1));
        x += 60;

        // 下一页
        this.addButton(new ButtonGeneric(x, y, 56, btnHeight, "下一页", "下一页（PageDown）"),
                (base, mouseButton) -> RecipeClientState.requestPage(RecipeClientState.page() + 1));
        x += 60;

        // 排序切换：服务端两种稳定序，客户端只报一个枚举
        boolean byName = RecipeClientState.sort() == RecipeQueryPayload.SORT_OUTPUT;
        this.addButton(new ButtonGeneric(x, y, 96, btnHeight,
                        "排序：" + (byName ? "按名字" : "最近学习"),
                        "按名字 = 产物 id 字典序（稳定）；最近学习 = 刚学会的在最前"),
                (base, mouseButton) -> RecipeClientState.setSort(byName
                        ? RecipeQueryPayload.SORT_RECENT
                        : RecipeQueryPayload.SORT_OUTPUT));
        x += 100;

        // 重试按钮恒在：按钮是在 initGui 里建的，而 NO_REPLY 要等 2 秒超时才会出现 ——
        // 若按 phase 决定加不加，它永远来不及被建出来（0.4.8 就是这么错过的）。
        // 正常浏览时点它 = 重新要一次索引（刷新）。
        this.addButton(new ButtonGeneric(x, y, 48, btnHeight, "重试",
                "重新问一次服务端：规则刚打开、或刚重开过仓库界面时用"),
                (base, mouseButton) -> RecipeClientState.retry());

        // 关闭
        this.addButton(new ButtonGeneric(this.getScreenWidth() - 66, y, 56, btnHeight, "关闭",
                "关掉配方本（虚空仓库界面不会自己回来，再右键仓库即可）"),
                (base, mouseButton) -> this.closeGui(true));

        // 提示行会占版面：先把列表位置摆对，别等下一帧才发现压住了第一行
        this.applyHintLayout(RecipeClientState.phase());
    }

    @Override
    public void removed() {
        current = null;
        // 关界面就把「等着再点一次」清掉：下次打开时不该有任何一行是红的
        RecipeClientState.clearPending();
        super.removed();
    }

    @Override
    public void drawContents(GuiContext ctx, int mouseX, int mouseY, float partialTicks) {
        // 🔴 先判超时：没有它，服务端不回包时这里会永远转圈
        RecipeClientState.tickProbe();
        this.refreshIfNeeded();

        // 状态行：已用 / 页码 / 总条数
        this.drawString(ctx,
                RecipeBrowseModel.usageLine(RecipeClientState.used(), RecipeClientState.MAX_RECIPES,
                        RecipeClientState.page(), RecipeClientState.total(),
                        RecipeClientState.pageSize()),
                12, 14, 0xFFFFFFFF);

        // 第二行：删除回执 / 翻页超时提示（没有内容时不画，避免一条空行）
        String status = RecipeClientState.status();
        if (!status.isEmpty()) {
            this.drawString(ctx, status, 12, 25, 0xFFFFD060);
        }

        // 提示行：PROBING / NO_REPLY 各自的说法，正常浏览时为空
        int y = LIST_Y;
        int color = hintColor();
        for (String line : this.hints) {
            if (line != null && !line.isEmpty()) {
                this.drawString(ctx, line, 12, y, color);
            }
            y += HINT_LINE_HEIGHT;
        }

        // 空本提示只在「真拿到了索引且一条都没有」时画 —— 探测阶段画这句是撒谎
        if (this.hints.isEmpty() && RecipeClientState.phase() == RecipeBrowseModel.Phase.READY
                && RecipeClientState.entries().isEmpty() && RecipeClientState.total() == 0) {
            this.drawString(ctx, "配方本是空的（合成成功一次就会记进来）", 12, LIST_Y, 0xFFAAAAAA);
        }

        super.drawContents(ctx, mouseX, mouseY, partialTicks);
    }

    /**
     * 数据/阶段变了就重建列表。
     *
     * <p>只在这里做，因为 {@code drawContents} 才是「渲染这一帧」的入口 —— 在别处改控件
     * 可能正好撞上 malilib 在遍历控件列表（那是 {@code ConcurrentModificationException}，
     * 但报错堆栈会指向渲染器，看着完全不像模组的问题）。</p>
     */
    private void refreshIfNeeded() {
        int rev = RecipeClientState.revision();
        if (rev == this.seenRevision) {
            return;
        }
        this.seenRevision = rev;
        this.applyHintLayout(RecipeClientState.phase());
        if (this.getListWidget() != null) {
            // 换页 / 换排序 / 删除之后滚动条必须回顶，否则新内容从半截开始显示
            this.getListWidget().resetScrollbarPosition();
        }
    }

    /** 重新算提示行 + 把列表摆到提示行下面（提示行数量变了列表高度也得跟着变） */
    private void applyHintLayout(RecipeBrowseModel.Phase phase) {
        this.hints = RecipeBrowseModel.hintLines(phase);
        this.setListPosition(LIST_X, LIST_Y + this.hintSpace());
        this.reCreateListWidget();
    }

    /** 提示行占掉的竖直空间（0 = 不占，正常浏览时版面与以前完全一样） */
    private int hintSpace() {
        return this.hints.isEmpty() ? 0 : this.hints.size() * HINT_LINE_HEIGHT + 4;
    }

    private int hintColor() {
        return switch (RecipeClientState.phase()) {
            case PROBING -> 0xFFAAAAAA;
            case NO_REPLY -> 0xFFFFC860;
            default -> 0xFFAAAAAA;
        };
    }
}
