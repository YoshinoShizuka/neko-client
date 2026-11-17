package neko.client;

import fi.dy.masa.malilib.gui.GuiListBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.render.GuiContext;
import neko.voidwarehouse.recipe.RecipePagePayload;
import neko.voidwarehouse.recipe.RecipeQueryPayload;

import java.util.List;

/**
 * 配方本界面：一整本连续滚动，顶上有搜索框，逐条可删。
 *
 * <h2>0.5.0 长什么样</h2>
 * <pre>
 * 已用 137 / 2048 · 已显示 32 / 137 条
 * ↓ 下面还有（继续往下滚）
 * ──────────────────────────────────────────────
 * [🔍 搜索产物名 / 物品 id                                    ]
 * ──────────────────────────────────────────────
 * [🗡] 铁剑 ×1       原料：铁锭 ×2、木棍 ×1          [删除]
 * [⛏] 铁镐 ×1       原料：铁锭 ×3、木棍 ×2          [删除]
 * …
 * ──────────────────────────────────────────────
 * [排序：按名字] [清空搜索] [重试]              [关闭]
 * </pre>
 *
 * <h2>0.5.0：三个变化，各自解决一个真机问题</h2>
 * <ol>
 *   <li><b>翻页按钮没了</b>：0.4.x 一页 8 条 + 18 次「下一页」，找一条配方等于猜页码。
 *       现在滚到底自动续拉（{@link RecipeClientState#noteVisibleRow}），页码不再进界面。</li>
 *   <li><b>加了搜索框</b>：名字匹配只能在客户端做（专用服务器没有中文语言表），
 *       匹配出的产物 id 白名单发给服务端重查，结果按匹配度置顶
 *       （{@link RecipeBrowseModel#matchRank}）。0.5.1 起支持拼音 / 首字母 /
 *       <b>模糊拼音</b>（zh→z、前后鼻音归一、n/l/r 与 f/h 各归一组）。</li>
 *   <li><b>每行加了物品图标</b>：两行密排文字里认东西太慢（{@link WidgetListRecipeEntry}）。</li>
 * </ol>
 *
 * <h2>为什么继承 {@code GuiListBase} 而不是自己画</h2>
 * <p>滚动、键盘导航、条目命中的容差、hover 提示、滚动条都是 malilib 已经写好的（Litematica 的
 * 材料列表同款）。自己画一遍只会多出三类 bug：滚轮方向、点偏 1px、滚动条不回位。
 * 这里只提供「列表从哪来」和「一行长什么样」。</p>
 *
 * <h2>🔴 回包之后怎么刷新（0.5.0 更正）</h2>
 * <p>0.4.9 的做法是「数据一变就 {@code reCreateListWidget()} + {@code initGui()}」。那能修好
 * 「控件里是空的」，但带来了新问题：<b>每次回包都重建控件，搜索框里玩家刚打的字就没了</b>，
 * 而且滚动位置被打回顶部。</p>
 * <p>现在分两种：<b>只有版面变了</b>（提示行出现/消失）才重建控件；<b>数据变了</b>只调
 * {@link WidgetListRecipe#refreshEntries()} —— 它是 public 的，做的正是
 * {@code initGui} 里装数据那一步（清空 {@code listContents}、重填、重建可见行控件），
 * 控件本身和搜索框都留着。</p>
 *
 * <h2>🔴 不再判「有没有开着仓库界面」（0.4.9 更正）</h2>
 * <p>第一版在这里判过 {@code containerMenu instanceof InventoryMenu}：不是容器界面就<b>连 hello 都不发</b>，
 * 理由是「服务端只认开着的仓库界面」。那个前提在服务端 1.0.17 就取消了
 * （{@code RecipeNetwork#dataOf} 只判规则与数据能不能取到），于是这条判断只剩一个效果：
 * 玩家在世界上按热键时，把一个本来会成功的请求挡在客户端，界面永远空着。</p>
 * <p>现在只做一件事：<b>发 hello，等 2 秒</b>。没回音才说话（{@link RecipeBrowseModel#hintLines}）。</p>
 */
public class RecipeBrowseGui extends GuiListBase<RecipePagePayload.Entry, WidgetListRecipeEntry, WidgetListRecipe> {

    private static final int LIST_X = 10;
    /** 列表默认顶边；有提示行时整体下移（见 {@link #hintSpace()}） */
    private static final int LIST_Y = 44;
    private static final int HINT_LINE_HEIGHT = 11;
    /**
     * 文本列与三行文本的纵坐标。
     *
     * <p>🔴 标题是 malilib 自己画的，位置写死在 <b>(20, 10)</b>（{@code GuiBase#drawTitle}，
     * 常量 {@code LEFT=20 / TOP=10}）。第一版把状态行放在 y=14 —— 和标题只差 4 像素，
     * 真机上「配方本」和「已用 15 / 2048」直接叠成一团。所以：标题占 10~19，状态行从 22 起。</p>
     */
    private static final int TEXT_X = 20;
    private static final int INFO_Y = 22;
    private static final int STATUS_Y = 33;
    private static final int HINT_Y = 44;

    private static RecipeBrowseGui current;

    /** 上次看到的 revision：变了才重装列表数据 */
    private int seenRevision = -1;

    /** 当前要画的提示行（空 = 正常浏览，不占版面） */
    private List<String> hints = List.of();

    /** 本次打开界面是否已经问过服务端（{@code initGui} 会被 resize 再调一次，别重复发 hello） */
    private boolean requestedOnOpen;

    /**
     * 搜索框原文的影子。
     *
     * <p>控件会被重建（版面变化 / resize），而重建出来的搜索框是空的 —— 必须把玩家打的字
     * 回填进去。这里存一份，重建时用它。</p>
     */
    private String filterText = "";

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
        // 🔴 按「整行数」从按钮行向上反推（0.5.1 布局修复）：以前写死 screenHeight - 100，
        //    与按钮行之间固定空 30px，再叠加行数取整的余数（最多 23px），底部最多空约 50px。
        //    现在可见几行就给多高：条目起点 = 列表顶边 + CONTENT_OVERHEAD（搜索框 23 + padding 4），
        //    列表底边（= 最后一行 + 4）始终贴住按钮行上方 4px，不再有固定预留。
        int top = LIST_Y + this.hintSpace();
        int bottom = this.getScreenHeight() - 30;   // 按钮行（高 20，y = H-26）上方留 4px
        int rows = Math.max(1, (bottom - top - WidgetListRecipe.CONTENT_OVERHEAD)
                / RecipeBrowseModel.ENTRY_HEIGHT);
        return rows * RecipeBrowseModel.ENTRY_HEIGHT + WidgetListRecipe.CONTENT_OVERHEAD;
    }

    @Override
    public void initGui() {
        current = this;
        super.initGui();

        // 只问一次：resize 也会走到这里，重复发 hello 没意义（服务端还有 100ms 限流）
        if (!this.requestedOnOpen) {
            this.requestedOnOpen = true;
            // 换语言后显示名会变（搜索结果与排序都靠它）
            SearchCatalogResolver.clearCache();
            RecipeClientState.open();
        }

        int y = this.getScreenHeight() - 26;
        int x = 10;
        int btnHeight = 20;

        // 排序切换：服务端两种稳定序，客户端只报一个枚举。
        // 🔴 按钮文字必须点击时更新：initGui 里算的那份是死的，点了不更新 = 「看起来没反应」
        //    的第一嫌疑（列表顺序其实变了，但按钮还写着旧字样）。
        // 🔴🔴 目标值必须在点击那一刻从当前状态现算，绝不能把 initGui 算好的 byName 捕获进
        //    lambda：那是定值，第一次点击翻转成功后，之后的每次点击发的都是同一个值 ——
        //    setSort 一看「请求值 == 当前值」就早退，表现正是「点一次有反应，再点就没反应」
        //    （0.5.1 真机日志实锤：一次会话内第 1 次有 page 0，之后全是空转）。
        boolean byName = RecipeClientState.sort() == RecipeQueryPayload.SORT_OUTPUT;
        ButtonGeneric sortButton = new ButtonGeneric(x, y, 96, btnHeight,
                        "排序：" + (byName ? "按名字" : "最近学习"),
                        "按名字 = 产物 id 字典序（稳定）；最近学习 = 刚学会的在最前");
        this.addButton(sortButton, (base, mouseButton) -> {
            RecipeClientState.setSort(RecipeClientState.sort() == RecipeQueryPayload.SORT_OUTPUT
                    ? RecipeQueryPayload.SORT_RECENT
                    : RecipeQueryPayload.SORT_OUTPUT);
            sortButton.setDisplayString("排序：" + (RecipeClientState.sort()
                    == RecipeQueryPayload.SORT_OUTPUT ? "按名字" : "最近学习"));
        });
        x += 100;

        // 清空搜索：搜索框是 malilib 的控件，没有对外的 setter，只能通过列表控件转发
        this.addButton(new ButtonGeneric(x, y, 72, btnHeight, "清空搜索",
                "把搜索框清空，回到「整本按顺序看」"),
                (base, mouseButton) -> {
                    RecipeClientState.clearSearch();
                    this.filterText = "";
                    WidgetListRecipe list = this.getListWidget();
                    if (list != null) {
                        list.clearFilterText();
                    }
                });
        x += 76;

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
        this.hints = RecipeBrowseModel.hintLines(RecipeClientState.phase());
        this.relayout();
        this.seenRevision = RecipeClientState.revision();
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
        // 搜索框的文字没有回调，只能每帧读一次（读的是控件里的原文，不是列表的过滤结果）
        WidgetListRecipe list = this.getListWidget();
        if (list != null) {
            this.filterText = list.filterText();
        }

        // 🔴 可见行数每帧现算（resize 会变）：预取裕量、以及「要不要再拉一页才能铺满屏幕」
        //    都由它决定 —— 一次显示多少条跟着屏幕走，不再写死。
        //    高度里含 CONTENT_OVERHEAD（搜索框 + padding），要扣掉才是真正的条目行数。
        RecipeClientState.setVisibleRows(Math.max(1,
                (this.getBrowserHeight() - WidgetListRecipe.CONTENT_OVERHEAD)
                        / RecipeBrowseModel.ENTRY_HEIGHT));

        // 🔴 先判超时与搜索防抖：没有它，服务端不回包时这里会永远转圈
        RecipeClientState.tickProbe(this.filterText);
        this.refreshIfNeeded();

        // 状态行：已用 / 已显示 / 搜索词
        this.drawString(ctx,
                RecipeBrowseModel.statusLine(RecipeClientState.used(), RecipeClientState.MAX_RECIPES,
                        RecipeClientState.entries().size(), RecipeClientState.total(),
                        RecipeClientState.search()),
                TEXT_X, INFO_Y, 0xFFFFFFFF);

        // 第二行：删除回执 / 请求超时 / 「下面还有」
        String status = RecipeClientState.status();
        if (!status.isEmpty()) {
            this.drawString(ctx, status, TEXT_X, STATUS_Y, 0xFFFFD060);
        } else if (RecipeClientState.hasMore()) {
            this.drawString(ctx, RecipeClientState.search().isEmpty()
                            ? "↓ 下面还有（继续往下滚会自动加载）"
                            : "正在按匹配度排序…",
                    TEXT_X, STATUS_Y, 0xFFAAAAAA);
        }

        // 提示行：PROBING / NO_REPLY 各自的说法，正常浏览时为空
        int y = HINT_Y;
        int color = hintColor();
        for (String line : this.hints) {
            if (line != null && !line.isEmpty()) {
                this.drawString(ctx, line, TEXT_X, y, color);
            }
            y += HINT_LINE_HEIGHT;
        }

        this.drawEmptyHint(ctx);

        super.drawContents(ctx, mouseX, mouseY, partialTicks);
    }

    /**
     * 「空的」和「没搜到」必须分开说。
     *
     * <p>画在搜索框下面那一行（列表顶边 + 24），因为列表自己占着上方 23px 的搜索框 ——
     * 直接画在 {@code LIST_Y} 会被它压住。</p>
     */
    private void drawEmptyHint(GuiContext ctx) {
        if (!this.hints.isEmpty() || RecipeClientState.phase() != RecipeBrowseModel.Phase.READY
                || !RecipeClientState.entries().isEmpty() || RecipeClientState.total() != 0) {
            return;
        }
        int y = LIST_Y + this.hintSpace() + 24;
        if (RecipeClientState.search().isEmpty()) {
            this.drawString(ctx, "配方本是空的（合成成功一次就会记进来）", TEXT_X, y, 0xFFAAAAAA);
        } else {
            this.drawString(ctx, "没有匹配的配方（换个词试试，也可以只打半个名字）",
                    TEXT_X, y, 0xFFAAAAAA);
        }
    }

    /**
     * 数据/版面变了就刷新列表。
     *
     * <p>只在这里做，因为 {@code drawContents} 才是「渲染这一帧」的入口 —— 在别处改控件
     * 可能正好撞上 malilib 在遍历控件列表（那是 {@code ConcurrentModificationException}，
     * 但报错堆栈会指向渲染器，看着完全不像模组的问题）。</p>
     */
    private void refreshIfNeeded() {
        List<String> nextHints = RecipeBrowseModel.hintLines(RecipeClientState.phase());
        boolean layoutChanged = !nextHints.equals(this.hints);
        int rev = RecipeClientState.revision();
        if (!layoutChanged && rev == this.seenRevision) {
            return;
        }
        this.seenRevision = rev;
        this.hints = nextHints;

        WidgetListRecipe list = this.getListWidget();
        if (list == null || layoutChanged) {
            // 版面变了 ⇒ 列表的位置/高度都变了，而 posX/posY 是构造参数 ⇒ 只能重建
            this.relayout();
            return;
        }
        // 只是数据变了 ⇒ 重装数据即可。搜索框里的字和滚动位置都留着（0.4.9 在这里重建控件，
        // 结果就是「打字打到一半，列表一刷新字没了、滚动条还跳回顶部」）。
        list.refreshEntries();
    }

    /**
     * 重建列表控件并把数据装进去。
     *
     * <p>🔴 {@code reCreateListWidget()} 只是 {@code new} 了一个控件，<b>不会装数据</b>：
     * malilib 的 {@code WidgetListBase} 把条目存在自己的 {@code listContents} 里，
     * 而往里面灌 {@code getAllEntries()} 的只有 {@code refreshEntries()}，
     * 它唯一的调用者是 {@code WidgetListBase#initGui()}（{@code GuiListBase#initGui} 会替我们调）。
     * 我们是在 {@code GuiListBase.initGui} <b>之后</b>才重建控件的，所以必须自己补
     * {@code setSize + initGui} —— 少了这一步，控件拿到的永远是空列表：
     * 真机 0.4.9 的表现就是「共 15 条」而底下一行都没有，且日志里一条异常都没有。</p>
     */
    private void relayout() {
        this.setListPosition(LIST_X, LIST_Y + this.hintSpace());
        this.reCreateListWidget();

        WidgetListRecipe list = this.getListWidget();
        if (list == null) {
            return;
        }
        // 与 GuiListBase.initGui 完全同序：先给尺寸（算 browserEntryWidth / 可见行数），再 initGui 装数据
        list.setSize(this.getBrowserWidth(), this.getBrowserHeight());
        list.initGui();
        // 重建出来的是新搜索框（空的）⇒ 把玩家打的字还回去
        list.setFilterText(this.filterText);
        // 换页 / 换排序 / 删除之后滚动条必须回顶，否则新内容从半截开始显示
        list.resetScrollbarPosition();
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
