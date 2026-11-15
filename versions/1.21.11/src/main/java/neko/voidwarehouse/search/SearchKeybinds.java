package neko.voidwarehouse.search;

import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;

/**
 * 把 malilib 的配置对象接到实际行为上（照 MiniHUD 的写法）。
 *
 * <h2>三件事</h2>
 * <ol>
 *   <li><b>挂回调</b>：两个开关热键按下时翻开关，打开配置界面的热键按下时开界面；</li>
 *   <li><b>登记热键表</b>：{@code addHotkeysForCategory} 让它们出现在配置界面的
 *       「热键」页里，并按 mod 分组；</li>
 *   <li><b>{@code updateUsedKeys()}</b>：把用到的键告诉 malilib，让它优先于原版
 *       按键绑定派发 —— 否则玩家把热键改到某个原版键位上时会「两个一起吃」。</li>
 * </ol>
 *
 * <h2>🔴 为什么回调里要先问「搜索框是不是聚焦着」</h2>
 * <p>热键设置是 {@code cancel=false}（见 {@link SearchConfig#HOTKEY_SETTINGS}），
 * 按键会继续交给界面 —— 这是为了让玩家能在搜索框里正常打字。但「继续交出去」
 * 和「照样触发」是两件事：如果玩家正在框里打 {@code h}（要找「红石」），
 * 那一下既要打出字符、又要把搜索框关掉，显然不对。所以这里先让路：
 * 聚焦时直接返回 false，不翻开关，字符照常进框。</p>
 *
 * <h2>为什么用动作栏提示而不是聊天栏</h2>
 * <p>「按一下、屏幕上闪一行」是 MiniHUD / Tweakeroo 的既有习惯，不刷聊天记录。
 * 提示文本走 {@code InfoUtils.printActionbarMessage(翻译键)} —— 它内部是
 * {@code Component.translatable}，所以要传键、不传译好的字。</p>
 */
public final class SearchKeybinds {

    /** 热键表里的分组名（显示在配置界面的热键页，品牌名不翻译） */
    private static final String CATEGORY = "Neko VoidWarehouse Search";

    private SearchKeybinds() {
    }

    /** 客户端初始化时调一次 */
    public static void register() {
        SearchConfig.SEARCH_BOX.getKeybind().setCallback(new ToggleCallback(SearchConfig.SEARCH_BOX,
                "message.neko_voidwarehouse_search.search_on",
                "message.neko_voidwarehouse_search.search_off"));
        SearchConfig.FILL_GESTURE.getKeybind().setCallback(new ToggleCallback(SearchConfig.FILL_GESTURE,
                "message.neko_voidwarehouse_search.fill_on",
                "message.neko_voidwarehouse_search.fill_off"));
        SearchConfig.OPEN_GUI.getKeybind().setCallback(new OpenGuiCallback());

        IKeybindManager manager = InputEventHandler.getKeybindManager();
        manager.addHotkeysForCategory(CATEGORY,
                StringUtils.translate("neko_voidwarehouse_search.hotkeys.category"),
                SearchConfig.OPTIONS);
        // 🔴 必须调：malilib 靠它算出「哪些键归我们」，进而让这些键优先于原版绑定
        manager.updateUsedKeys();
    }

    /** 翻一个布尔开关，并把结果说一声 */
    private static final class ToggleCallback implements IHotkeyCallback {

        private final ConfigBooleanHotkeyed config;
        private final String onKey;
        private final String offKey;

        private ToggleCallback(ConfigBooleanHotkeyed config, String onKey, String offKey) {
            this.config = config;
            this.onKey = onKey;
            this.offKey = offKey;
        }

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind keybind) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            // 正在搜索框里打字：这一下属于输入，不属于开关（字符会照常进框）
            if (SearchClientState.isSearchFocused()) {
                return false;
            }
            this.config.toggleBooleanValue();
            boolean on = this.config.getBooleanValue();
            // 立刻落盘：玩家按完就去干别的了，崩一次不该把设置吞掉
            SearchConfig.saveToFile();
            // 当前正开着容器界面就地刷新：关搜索框=藏框并撤掉服务端过滤，打开=立刻可见
            SearchClientState.applyFeatureStateToCurrentScreen();
            InfoUtils.printActionbarMessage(on ? this.onKey : this.offKey);
            return true;
        }
    }

    /** 打开配置界面（malilib 的 {@code GuiBase.openGui} 自己处理各版本的 setScreen 差异） */
    private static final class OpenGuiCallback implements IHotkeyCallback {

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind keybind) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            GuiBase.openGui(new SearchConfigGui());
            return true;
        }
    }
}
