package neko.client;

import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.MaLiLibIcons;
import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import neko.voidwarehouse.recipe.RecipePagePayload;

import java.util.Collection;
import java.util.List;

/**
 * 配方本的列表控件。数据源是 {@link RecipeClientState#entries()}（<b>累积</b>的条目池）。
 *
 * <h2>0.5.0：一页 8 条 → 一整本连续滚动</h2>
 * <p>0.4.x 里这个列表只装「当前页 8 条」，翻页靠外面的两个按钮。现在它装的是
 * 「已经拉下来的全部条目」：滚到接近底部时 {@link RecipeClientState#noteVisibleRow} 记下的
 * 行号会让状态机去续拉下一页，玩家看到的是列表自己变长。
 * 页码从此不出现在界面上（{@link RecipeBrowseModel#statusLine} 只报「已显示 X / Y 条」）。</p>
 *
 * <h2>0.5.0：搜索框用的是 malilib 自己的那个</h2>
 * <p>{@code widgetSearchBar} 是 {@link WidgetListBase} 的 {@code protected} 字段，
 * 由子类负责创建（malilib 的配置列表也是这么干的：见 {@code WidgetListConfigOptions} 构造器）。
 * 一旦它不是 null，{@code WidgetListBase} 就会自己负责：画它、把鼠标点击先递给它、
 * 把键盘输入递给它、并在文字变化后 {@code refreshBrowserEntries()}。</p>
 *
 * <p>🔴 <b>但本地过滤必须关掉</b>：0.4.x 把 {@code hasFilter()} 设成 false，理由是
 * 「只能筛当前页，会骗人」。现在数据是服务端<b>按白名单</b>筛过的，本地再筛一次
 * 会和服务端的结果对不上（比如服务端按 id 命中、本地按名字筛不到）。所以保留搜索框、
 * 让 {@link #entryMatchesFilter} 恒真 —— 搜索词只用来<b>驱动服务端重查</b>，不用来筛控件。</p>
 *
 * <h2>为什么不重写 onMouseClicked / onKeyTyped</h2>
 * <p>{@code WidgetListBase} 的鼠标/键盘方法参数类型在两代客户端上不一样
 * （26.x 是 {@code MouseButtonEvent}/{@code KeyEvent}，1.21.11 是混淆名
 * {@code class_11909}/{@code class_11908}）。<b>只要不重写它们，这个类就能五版本逐字节相同</b> ——
 * 删除按钮的点击由条目里的 {@code ButtonGeneric} 自己处理，搜索框的输入由 malilib 自己转发。</p>
 */
public class WidgetListRecipe extends WidgetListBase<RecipePagePayload.Entry, WidgetListRecipeEntry> {

    /** 搜索框高度（malilib 配置列表同款 20）与它占掉的顶部空间 */
    private static final int SEARCH_BAR_HEIGHT = 20;
    private static final int SEARCH_BAR_OFFSET = 23;

    public WidgetListRecipe(int x, int y, int width, int height) {
        super(x, y, width, height, null);
        // 一行 24：图标 16 + 标题行 + 原料行，上下留白。
        // 🔴 行高只有一个来源：RecipeBrowseModel.ENTRY_HEIGHT —— 界面按它换算「可见几行」，
        //    两边各写一个 24，改了一边就会出现「预取裕量算错」这种最难查的偏差。
        this.browserEntryHeight = RecipeBrowseModel.ENTRY_HEIGHT;
        this.allowKeyboardNavigation = true;

        // 搜索框：与 WidgetListConfigOptions 完全同款的位置/尺寸算法
        this.widgetSearchBar = new WidgetRecipeSearchBar(x + 2, y + 4,
                Math.max(40, width - 14), SEARCH_BAR_HEIGHT, 0,
                MaLiLibIcons.SEARCH, LeftRight.LEFT);
        // 条目从搜索框下面开始（少了这一行，第一行会被搜索框压住）
        this.browserEntriesOffsetY = SEARCH_BAR_OFFSET;
    }

    /** 数据源：累计已拉到的条目（服务端给的是稳定序，客户端只在搜索态重排） */
    @Override
    protected Collection<RecipePagePayload.Entry> getAllEntries() {
        return RecipeClientState.entries();
    }

    /** 🔴 不要本地重排：浏览态用的是服务端稳定序，重排会让「续拉的页」插错位置 */
    @Override
    protected boolean getShouldSortList() {
        return false;
    }

    /**
     * 🔴 恒真：搜索词只用来驱动服务端重查（见类注释）。
     *
     * <p>如果不重写，malilib 会拿 {@link #getEntryStringsForFilter} 的字串做本地包含匹配，
     * 于是「服务端按 id 命中、本地按名字筛不到」的条目会被静默隐藏 —— 玩家看到的是
     * 「明明匹配却少了几个」。{@code matchesFilter} 的实现在各版本都一样，重写它是安全的。</p>
     */
    @Override
    protected boolean entryMatchesFilter(RecipePagePayload.Entry entry, String filterText) {
        return true;
    }

    @Override
    protected List<String> getEntryStringsForFilter(RecipePagePayload.Entry entry) {
        return List.of(entry.output());
    }

    /**
     * 搜索框里的原文（界面每帧读它喂给状态机）。
     *
     * <p>🔴 不能直接用 {@code getFilterText()}：它内部是
     * {@code widgetSearchBar.getFilter()}，而那个方法<b>会转小写</b>（还要求搜索框是展开的）。
     * 转小写对匹配没影响，但回填到输入框里就会把玩家打的「IRON」改成「iron」——
     * 重建控件一次，玩家就看见自己的大写字母被吃掉。所以这里读原文。</p>
     */
    public String filterText() {
        if (this.widgetSearchBar instanceof WidgetRecipeSearchBar bar) {
            return bar.text();
        }
        return this.getFilterText();
    }

    /** 回填搜索词（控件被重建后用，见 {@link WidgetRecipeSearchBar}） */
    public void setFilterText(String text) {
        if (this.widgetSearchBar instanceof WidgetRecipeSearchBar bar) {
            bar.setText(text);
        }
    }

    /** 「清空搜索」按钮 */
    public void clearFilterText() {
        this.setFilterText("");
    }

    /**
     * 每建一行就把行号报给状态机 —— 这是「滚到底该续拉了」的唯一判据。
     *
     * <p>不读滚动条字段：{@code scrollbarValue} 之类在 1.21.11 会被 remap 成
     * {@code field_xxxx}，源码层面必须分版本写。而行号是我们自己传进去的参数，五版本一致。</p>
     */
    @Override
    protected WidgetListRecipeEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
                                                         RecipePagePayload.Entry entry) {
        RecipeClientState.noteVisibleRow(listIndex);
        return new WidgetListRecipeEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex);
    }
}
