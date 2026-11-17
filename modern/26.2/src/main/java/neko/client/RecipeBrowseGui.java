package neko.client;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import neko.voidwarehouse.recipe.RecipePagePayload;
import neko.voidwarehouse.recipe.RecipeQueryPayload;

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
 * <h2>这一版没有文本搜索</h2>
 * <p>服务端分页 ⇒ 客户端过滤只能筛当前页 8 条，搜「铁锭」而它在第 9 页时会被判「没有」。
 * 真搜索要把匹配出的产物 id 白名单发给服务端重取（{@code recipe_query.outputs} 已经支持），
 * 那需要输入框 + 跨版本键盘分发，留到下一版。当前按产物名排序 + 翻页，实测存档
 * （learned 十几条到一两百条）完全够用。</p>
 */
public class RecipeBrowseGui extends GuiListBase<RecipePagePayload.Entry, WidgetListRecipeEntry, WidgetListRecipe> {

    private static RecipeBrowseGui current;

    /** 上次看到的 revision：变了才重建列表 */
    private int seenRevision = -1;

    public RecipeBrowseGui() {
        super(10, 36);
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
        return Math.max(72, this.getScreenHeight() - 100);
    }

    @Override
    public void initGui() {
        current = this;
        super.initGui();

        // 第一次打开才要索引；已经 READY 的话列表里就有数据，重开界面不该再拉一遍
        if (RecipeClientState.phase() == RecipeClientState.Phase.IDLE) {
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

        // 关闭
        this.addButton(new ButtonGeneric(this.getScreenWidth() - 66, y, 56, btnHeight, "关闭",
                "关掉界面（配方本本身不会变）"),
                (base, mouseButton) -> this.closeGui(true));
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
        this.refreshIfNeeded();

        // 状态行：已用 / 页码 / 总条数
        this.drawString(ctx,
                RecipeBrowseModel.usageLine(RecipeClientState.used(), RecipeClientState.MAX_RECIPES,
                        RecipeClientState.page(), RecipeClientState.total(),
                        RecipeClientState.pageSize()),
                12, 14, 0xFFFFFFFF);

        // 第二行：删除回执 / 加载提示 / 不支持说明（没有内容时不画，避免一条空行）
        String status = RecipeClientState.status();
        if (!status.isEmpty()) {
            this.drawString(ctx, status, 12, 25, 0xFFFFD060);
        }

        if (RecipeClientState.phase() == RecipeClientState.Phase.PROBING) {
            this.drawString(ctx, "正在向服务端要配方本…", 12, 36, 0xFFAAAAAA);
        } else if (RecipeClientState.entries().isEmpty() && RecipeClientState.total() == 0) {
            this.drawString(ctx, "配方本是空的（合成成功一次就会记进来）", 12, 36, 0xFFAAAAAA);
        }

        super.drawContents(ctx, mouseX, mouseY, partialTicks);
    }

    /**
     * 数据变了就重建列表。
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
        this.reCreateListWidget();
        // 换页 / 换排序 / 删除之后滚动条必须回顶，否则新内容从半截开始显示
        if (this.getListWidget() != null) {
            this.getListWidget().resetScrollbarPosition();
        }
    }
}
