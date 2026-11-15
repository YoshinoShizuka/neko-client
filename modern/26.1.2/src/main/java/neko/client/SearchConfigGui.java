package neko.client;

import fi.dy.masa.malilib.gui.GuiConfigsBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.button.IButtonActionListener;
import fi.dy.masa.malilib.gui.interfaces.IConfigGuiAllTab;
import fi.dy.masa.malilib.util.StringUtils;

import java.util.List;

/**
 * 游戏内配置界面 —— 继承 malilib 的 {@link GuiConfigsBase}，条目本身一行都不用画。
 *
 * <h2>玩家看到什么</h2>
 * <p>和 MiniHUD 的配置界面同一副样子：<b>顶部一排分类按钮</b>，每行左边是名字 + 复选框、
 * 右边是按键按钮，悬停显示注释；点按键按钮进入「按下新键」状态，按 ESC 取消、按 Delete 清空；
 * 右上角的下拉框能直接跳到别的装了 malilib 配置界面的模组 —— 那些都是
 * {@code GuiConfigsBase} 白送的。</p>
 *
 * <h2>分类怎么做的（照抄 MiniHUD 的 {@code GuiConfigs}）</h2>
 * <p>🔴 <b>malilib 的 {@code GuiConfigsBase} 本身<b>没有</b>分类功能</b>：它只有一个
 * {@link #getConfigs()} 列表。MiniHUD 那排「Generic / Colors / Info Lines…」是它自己在
 * {@code initGui()} 里加的按钮，切换一个静态的 {@code tab} 字段后
 * {@code reCreateListWidget()} + {@code initGui()} 让列表重新问一次 {@link #getConfigs()}。
 * 这里照同一套做法，因为只有这样才和玩家装的其他 masa 系模组长得一样。</p>
 *
 * <p>「全部」那一页的标题键直接用 malilib 自带的
 * {@link IConfigGuiAllTab#getTranslationKey()}（{@code malilib.gui.title.all}，
 * 中文已经翻译成「全部」），不用自己再写一条。</p>
 *
 * <h2>为什么「快捷键」页只有一条</h2>
 * <p>两个功能开关是 {@code ConfigBooleanHotkeyed}：<b>一行里同时有复选框和按键按钮</b>，
 * 它们的热键本来就摆在功能自己那一行上。所以「通用」放两个功能开关、
 * 「快捷键」只放纯热键（目前只有「打开配置界面」一条）—— 这正是 MiniHUD 的分法
 * （它的 {@code GENERIC_HOTKEYS} 页也只放 {@code Hotkeys.HOTKEY_LIST}）。</p>
 */
public class SearchConfigGui extends GuiConfigsBase implements IConfigGuiAllTab {

    /** 当前分类。静态 ⇒ 关掉再开还停在原页（MiniHUD 同款） */
    private static Tab tab = Tab.ALL;

    /**
     * 参数：左右留白、**列表顶边**、mod id、父界面（null = 关掉后回游戏）、标题翻译键。
     *
     * <p>顶边传 50 而不是 10：分类按钮占的是 y=26 那一行（高 20），列表从 50 起才不被压住。</p>
     *
     * <p>父界面传 {@code null} 而不是 {@code Minecraft.screen}：26.1.x 的
     * {@code Minecraft.screen} 是公开字段、26.2+ 挪进了 {@code Gui#screen()}，
     * 在这里取哪个都会有一半版本编译不过 —— 干脆不传，让 malilib 自己处理。</p>
     */
    public SearchConfigGui() {
        super(10, 50, SearchConfig.MOD_ID, null, SearchConfig.MOD_ID + ".gui.title");
    }

    @Override
    public void initGui() {
        super.initGui();
        // 清掉上一页残留的「正在改键」状态与改键监听器，否则换页后按钮可能还挂着旧回调
        this.clearOptions();

        int x = 10;
        int y = 26;
        int rows = 1;

        for (Tab candidate : Tab.values()) {
            if (!this.useAllTab() && candidate == Tab.ALL) {
                continue;
            }
            int width = this.getStringWidth(candidate.getDisplayName()) + 10;
            if (x >= this.getScreenWidth() - width - 10) {
                x = 10;
                y += 22;
                rows++;
            }
            x += this.createTabButton(x, y, width, candidate);
        }

        // 分类按钮折成两行时把列表整体下移，别让第二行按钮压住第一条配置
        if (rows > 1) {
            int scrollbarPosition = this.getListWidget().getScrollbar().getValue();
            this.setListPosition(this.getListX(), 50 + (rows - 1) * 22);
            this.reCreateListWidget();
            this.getListWidget().getScrollbar().setValue(scrollbarPosition);
            this.getListWidget().refreshEntries();
        }
    }

    private int createTabButton(int x, int y, int width, Tab candidate) {
        ButtonGeneric button = new ButtonGeneric(x, y, width, 20, candidate.getDisplayName());
        // 当前页的按钮置灰（MiniHUD 同款：不是隐藏，是「已经在这一页」）
        button.setEnabled(tab != candidate);
        this.addButton(button, new TabListener(candidate, this));
        return button.getWidth() + 2;
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
        switch (tab) {
            case ALL:
                return ConfigOptionWrapper.createFor(SearchConfig.OPTIONS);
            case GENERIC:
                return ConfigOptionWrapper.createFor(SearchConfig.GUI_FEATURES);
            case HOTKEYS:
            default:
                return ConfigOptionWrapper.createFor(SearchConfig.GUI_HOTKEYS);
        }
    }

    /** 「全部」页把每个分类都摊一遍；按钮列表里的「全部」按这个返回值决定显不显示 */
    @Override
    public boolean useAllTab() {
        return true;
    }

    @Override
    public List<ConfigOptionWrapper> getAllConfigs() {
        return ConfigOptionWrapper.createFor(SearchConfig.OPTIONS);
    }

    @Override
    protected void onSettingsChanged() {
        SearchConfig.saveToFile();
        SearchClientState.applyFeatureStateToCurrentScreen();
    }

    @Override
    public void removed() {
        SearchConfig.saveToFile();
        super.removed();
    }

    /** 分类。名字全走翻译键，「全部」那条由 malilib 提供 */
    private enum Tab {
        ALL(IConfigGuiAllTab.getTranslationKey()),
        GENERIC("neko_client.gui.tab.generic"),
        HOTKEYS("neko_client.gui.tab.hotkeys");

        private final String translationKey;

        Tab(String translationKey) {
            this.translationKey = translationKey;
        }

        String getDisplayName() {
            return StringUtils.translate(this.translationKey);
        }
    }

    /** 点分类：换静态 tab → 重建列表（宽度可能变）→ 重跑 initGui 画按钮 */
    private record TabListener(Tab candidate, SearchConfigGui parent) implements IButtonActionListener {

        @Override
        public void actionPerformedWithButton(ButtonBase button, int mouseButton) {
            SearchConfigGui.tab = this.candidate;
            this.parent.reCreateListWidget();
            if (this.parent.getListWidget() != null) {
                this.parent.getListWidget().resetScrollbarPosition();
            }
            this.parent.initGui();
        }
    }
}
