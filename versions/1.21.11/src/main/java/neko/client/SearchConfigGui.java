package neko.client;

import fi.dy.masa.malilib.gui.GuiConfigsBase;

import java.util.List;

/**
 * 游戏内配置界面 —— 直接继承 malilib 的 {@link GuiConfigsBase}，一行代码都不用画。
 *
 * <h2>玩家看到什么</h2>
 * <p>和 MiniHUD 的配置界面同一副样子：每行左边是名字 + 复选框、右边是按键按钮，
 * 悬停显示注释；点按键按钮进入「按下新键」状态，按 ESC 取消、按 Delete 清空；
 * 右上角的下拉框能直接跳到别的装了 malilib 配置界面的模组 —— 那些都是
 * {@code GuiConfigsBase} 白送的，本类只提供「列出哪几个条目」和「什么时候存盘」。</p>
 *
 * <h2>三个覆写各自管什么</h2>
 * <ul>
 *   <li>{@link #getConfigs()}：条目清单（顺序 = 界面里的行序）；</li>
 *   <li>{@link #onSettingsChanged()}：勾选框 / 热键一改就调 —— 立刻写盘，
 *       并让当前开着的容器界面按新配置刷新（改完搜索框开关就能看见效果，不用关界面）；</li>
 *   <li>{@link #removed()}：界面关掉时再存一次（防止某些改动没走到上面那条路径）。</li>
 * </ul>
 */
public class SearchConfigGui extends GuiConfigsBase {

    /**
     * 参数：左右留白、上下留白、mod id、父界面（null = 关掉后回游戏）、标题翻译键。
     *
     * <p>父界面传 {@code null} 而不是 {@code Minecraft.screen}：26.1.x 的
     * {@code Minecraft.screen} 是公开字段、26.2+ 挪进了 {@code Gui#screen()}，
     * 在这里取哪个都会有一半版本编译不过 —— 干脆不传，让 malilib 自己处理。</p>
     */
    public SearchConfigGui() {
        super(10, 10, SearchConfig.MOD_ID, null, SearchConfig.MOD_ID + ".gui.title");
    }

    @Override
    public List<ConfigOptionWrapper> getConfigs() {
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
}
