package neko.client;

import fi.dy.masa.malilib.gui.widgets.WidgetListBase;
import neko.voidwarehouse.recipe.RecipePagePayload;

import java.util.Collection;
import java.util.List;

/**
 * 配方本的列表控件。数据源是 {@link RecipeClientState#entries()}（服务端发来的当前页）。
 *
 * <h2>为什么一页只有 8 条却要一个「列表」</h2>
 * <p>因为滚动、键盘上下选、条目命中判定这些都不该自己写 —— malilib 的
 * {@link WidgetListBase} 已经做了十几年了（Litematica 的材料列表就是它）。
 * 这里只负责「数据从哪来」和「一行长什么样」两件事。</p>
 *
 * <h2>为什么不重写 onMouseClicked</h2>
 * <p>{@code WidgetListBase} 的鼠标方法参数类型在两代客户端上不一样
 * （26.x 是 {@code net.minecraft.client.input.MouseButtonEvent}，1.21.11 是混淆名
 * {@code class_11909}）。<b>只要不重写它，这个类就能五版本逐字节相同</b> ——
 * 删除按钮的点击由条目里的 {@code ButtonGeneric} 自己处理，不经过这里。</p>
 *
 * <h2>为什么要关掉自带搜索栏</h2>
 * <p>服务端是<b>分页</b>的：自带过滤只能筛当前页这 8 条，玩家搜「铁锭」而铁锭在第 9 页
 * 时会被判「没有这条」——比没有搜索更糟。真正的搜索必须把匹配出来的<b>产物 id 白名单</b>
 * 发给服务端重取（{@code recipe_query.outputs}），那是下一版的事；
 * 这一版先把「浏览 + 删除」做扎实，列表按产物名排序（服务端保证的稳定序），翻页可预期。</p>
 */
public class WidgetListRecipe extends WidgetListBase<RecipePagePayload.Entry, WidgetListRecipeEntry> {

    public WidgetListRecipe(int x, int y, int width, int height) {
        super(x, y, width, height, null);
        // 一行 24：标题 10 + 原料 10 + 上下留白 4
        this.browserEntryHeight = 24;
        this.allowKeyboardNavigation = true;
    }

    /** 数据源：当前页的条目（服务端已经排好序，客户端不再排） */
    @Override
    protected Collection<RecipePagePayload.Entry> getAllEntries() {
        return RecipeClientState.entries();
    }

    /** 🔴 不要本地重排：服务端给的是稳定序（产物 id → recipeId），重排会让翻页出现重复/漏条 */
    @Override
    protected boolean getShouldSortList() {
        return false;
    }

    /** 关掉自带搜索栏：它只能筛当前页，会骗人（见类注释） */
    @Override
    protected boolean hasFilter() {
        return false;
    }

    @Override
    protected List<String> getEntryStringsForFilter(RecipePagePayload.Entry entry) {
        return List.of(entry.output());
    }

    @Override
    protected WidgetListRecipeEntry createListEntryWidget(int x, int y, int listIndex, boolean isOdd,
                                                         RecipePagePayload.Entry entry) {
        return new WidgetListRecipeEntry(x, y, this.browserEntryWidth, this.browserEntryHeight,
                isOdd, entry, listIndex);
    }
}
