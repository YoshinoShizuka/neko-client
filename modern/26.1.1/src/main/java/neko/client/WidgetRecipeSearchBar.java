package neko.client;

import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.interfaces.IGuiIcon;
import fi.dy.masa.malilib.gui.widgets.WidgetSearchBar;

/**
 * malilib 搜索框的一层薄壳子：只补两件它没给的事 —— <b>取原文</b>与<b>写原文</b>。
 *
 * <h2>为什么不自己写一个输入框</h2>
 * <p>输入框是整条链路上最容易跨版本出错的东西：26.x 的按键事件是
 * {@code KeyEvent}/{@code CharacterEvent}，1.21.11 是 {@code class_11907}/{@code class_11908}，
 * 连方法名都被 remap 过。<b>自己不碰输入事件</b>，就能让这个文件五版本逐字节相同。
 * malilib 的 {@link WidgetSearchBar} 内部用的是它自己的 {@code GuiTextFieldGeneric}，
 * 键盘、光标、选区、输入法全部它管 —— 我们只负责读结果。</p>
 *
 * <h2>为什么要 {@code setSearchOpen(true)}</h2>
 * <p>malilib 的搜索框默认是<b>收起来的一个放大镜图标</b>（配置界面里点一下才展开）。
 * 对配置界面那样「偶尔搜一次」的界面没问题，但配方本 0.5.0 的搜索是主入口之一：
 * 收成图标的话没人知道能搜。这里恒展开。</p>
 *
 * <h2>为什么需要写原文</h2>
 * <p>{@link WidgetListRecipe} 在数据/版面变化时会被整个重建（{@code reCreateListWidget}），
 * 新控件里的搜索框是空的 —— 不把玩家刚打的字还回去，输入到一半列表一刷新就白打了。
 * {@code searchBox} 是 {@code protected}，只有子类碰得到，这就是这个类存在的全部理由。</p>
 */
public class WidgetRecipeSearchBar extends WidgetSearchBar {

    public WidgetRecipeSearchBar(int x, int y, int width, int height, int maxLength,
                                 IGuiIcon icon, LeftRight alignment) {
        super(x, y, width, height, maxLength, icon, alignment);
        this.setSearchOpen(true);
    }

    /**
     * 输入框里的<b>原文</b>。
     *
     * <p>不能用 {@link #getFilter()}：那个在搜索框收起时返回空串、并且会转小写。
     * 回填时要的是玩家看到的那几个字。</p>
     */
    public String text() {
        String value = this.searchBox.getValue();
        return value == null ? "" : value;
    }

    /** 回填文字（重建控件后用），顺手保证框是展开的 */
    public void setText(String text) {
        this.searchBox.setValue(text == null ? "" : text);
        this.setSearchOpen(true);
    }
}
