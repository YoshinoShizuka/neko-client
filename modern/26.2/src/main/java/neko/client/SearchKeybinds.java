package neko.client;

import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IHotkeyCallback;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.StringUtils;
import neko.client.fill.ContinuousFillControl;

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
    private static final String CATEGORY = "Neko Client";

    private SearchKeybinds() {
    }

    /** 客户端初始化时调一次 */
    public static void register() {
        SearchConfig.SEARCH_BOX.getKeybind().setCallback(new ToggleCallback(SearchConfig.SEARCH_BOX,
                "message.neko_client.search_on",
                "message.neko_client.search_off"));
        SearchConfig.FILL_GESTURE.getKeybind().setCallback(new ToggleCallback(SearchConfig.FILL_GESTURE,
                "message.neko_client.fill_on",
                "message.neko_client.fill_off"));
        SearchConfig.OPEN_GUI.getKeybind().setCallback(new OpenGuiCallback());
        SearchConfig.OPEN_RECIPES.getKeybind().setCallback(new OpenRecipesCallback());
        SearchConfig.WAREHOUSE_TAKE.getKeybind().setCallback(new WarehouseTakeCallback());
        SearchConfig.CONTINUOUS_FILL.getKeybind().setCallback(new ContinuousFillCallback());

        // 🔴 SearchConfig.FILL_COMBO **刻意不挂回调**：它不是一个「按一下就做点什么」的热键，
        // 而是「按住哪些键再点鼠标」的修饰组合，由 MixinAbstractContainerScreen 在
        // mouseClicked 里判（见 SearchConfig#isFillComboHeld）。挂上回调反而会多出
        // 「按下的那一刻就取满某格」这种没有目标格的语义，而且会和点击重复触发。
        // 它仍然要进下面的 category：这样配置界面里能显示它、能查撞键。

        IKeybindManager manager = InputEventHandler.getKeybindManager();
        manager.addHotkeysForCategory(CATEGORY,
                StringUtils.translate("neko_client.hotkeys.category"),
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

    /**
     * 打开配方本界面。
     *
     * <p>对端不支持时（{@code phase == UNSUPPORTED}）只闪一句动作栏提示，不开空界面 ——
     * 一个永远空着的列表比「没有这个功能」更让人困惑。</p>
     */
    private static final class OpenRecipesCallback implements IHotkeyCallback {

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind keybind) {
            if (action != KeyAction.PRESS) {
                return false;
            }
            if (SearchClientState.isSearchFocused()) {
                return false;
            }
            if (RecipeClientState.phase() == RecipeBrowseModel.Phase.UNSUPPORTED) {
                InfoUtils.printActionbarMessage("message.neko_client.recipes_unsupported");
                return true;
            }
            GuiBase.openGui(new RecipeBrowseGui());
            return true;
        }
    }

    /**
     * 仓库补货：手持什么就向虚空仓库要什么（0.5.3）。
     *
     * <p>纯热键、不需要开界面；{@link WarehouseTakeNetwork#begin} 内部会自检手持物、
     * 通道支持与「是否已有请求在飞」。搜索框聚焦时不触发（那一击属于输入）。</p>
     */
    private static final class WarehouseTakeCallback implements IHotkeyCallback {

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind keybind) {
            if (action != KeyAction.PRESS || SearchClientState.isSearchFocused()) {
                return false;
            }
            return WarehouseTakeNetwork.begin(net.minecraft.client.Minecraft.getInstance());
        }
    }

    /**
     * 连续填充（0.5.4 修复）：任意绑定键按一下启用、再按一下关闭。
     *
     * <p>为什么不用「按住」：26.x 的 malilib 用坏掉的 isKeyDown 每帧清 PRESSED_KEYS，
     * 键盘键的 held 状态不可信；而热键 PRESS 回调走的是 malilib 自己的事件派发，
     * 其他热键（打开界面 / 仓库补货）都在用它。切换状态放在填充引擎的纯类里，
     * 便于单测与产物断言。</p>
     */
    private static final class ContinuousFillCallback implements IHotkeyCallback {

        @Override
        public boolean onKeyAction(KeyAction action, IKeybind keybind) {
            if (action != KeyAction.PRESS || SearchClientState.isSearchFocused()) {
                return false;
            }
            boolean on = ContinuousFillControl.toggle();
            InfoUtils.printActionbarMessage(on
                    ? "message.neko_client.continuous_fill_on"
                    : "message.neko_client.continuous_fill_off");
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
