package neko.client;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 两个功能的开关 + 四个热键，<b>全部由 malilib 持有</b>（照 MiniHUD 的做法）。
 *
 * <h2>为什么是 {@code ConfigBooleanHotkeyed}</h2>
 * <p>它是「布尔开关 + 一个热键」的合体：在配置界面里显示成<b>一行</b>
 * （左边复选框、右边按键按钮），按热键等于点那个复选框 —— 这正是 MiniHUD
 * 每个信息行 / 渲染开关的样子。所以「开关」和「热键」不是两套东西，
 * 改热键和改开关在同一个界面、同一次保存里完成。</p>
 *
 * <h2>🔴 为什么热键必须走 malilib，而不是原版 {@code KeyMapping}</h2>
 * <p>原版按键绑定<b>只在没有界面打开时才会被派发</b>（{@code Minecraft.screen != null}
 * 时 {@code KeyMapping.click} 根本不会被调用）—— 而这个模组的两个功能，玩家
 * 恰恰都是<b>在仓库界面打开着</b>的时候要切的：按 H 藏掉搜索框、按 J 关掉取满手势。
 * 用原版按键绑定的话，这两个键在真正的使用场景里一次都不会触发。</p>
 *
 * <p>malilib 的热键带 {@code KeybindSettings.Context}：这里用 {@code ANY}
 * （游戏内 + 任何界面都响应），并且 <b>{@code cancel=false}</b> ——
 * 按键不被吞掉，所以搜索框聚焦时按 H 还是正常打出 {@code h}（回调里也会
 * 主动让路，见 {@link SearchKeybinds}）。</p>
 *
 * <h2>落盘</h2>
 * <p>仍然是 {@code config/neko-client.json}，格式由
 * {@link SearchConfigJson} 这一层负责（纯函数、可单测）。本类只做
 * 「malilib 配置对象 ↔ 那五个字段」的搬运，并实现 {@link IConfigHandler}
 * 让 malilib 的 {@code ConfigManager} 也能触发加载 / 保存。</p>
 */
public final class SearchConfig implements IConfigHandler {

    /** mod id，同时是 malilib 配置管理的 key */
    public static final String MOD_ID = "neko_client";

    /**
     * 翻译键前缀。malilib 的 {@code apply()} 会把它拼成
     * {@code <prefix>.name.<配置名>} / {@code <prefix>.comment.<配置名>}，
     * 与 MiniHUD 的 {@code minihud.config.xxx.name.yyy} 完全同一套规则。
     */
    private static final String PREFIX = MOD_ID + ".config";

    /**
     * 热键设置：<b>游戏内和界面里都响应</b>，按下即触发，且<b>不吞按键</b>。
     *
     * <p>参数顺序（javap {@code KeybindSettings.create} 实证）：
     * context、activateOn、allowExtraKeys、orderSensitive、exclusive、cancel、allowEmpty。
     * 第二个 {@code false} 是 cancel —— 置 true 的话搜索框里就打不出 {@code h} 了。</p>
     */
    private static final KeybindSettings HOTKEY_SETTINGS = KeybindSettings.create(
            KeybindSettings.Context.ANY,   // 界面打开时也要响应（原版按键绑定做不到）
            KeyAction.PRESS,               // 按下即触发，不等松开
            true,                          // allowExtraKeys：多按了 Shift 之类不算错
            false,                         // orderSensitive：组合键不要求按下顺序
            false,                         // exclusive：不排他
            false,                         // cancel：不吞按键（否则会吃掉输入框里的字符）
            true);                         // allowEmpty：允许玩家把热键清空

    /** 容器界面里的搜索框开关（热键默认未绑定，由玩家自己设） */
    public static final ConfigBooleanHotkeyed SEARCH_BOX = new ConfigBooleanHotkeyed(
            "searchBox", SearchConfigJson.DEFAULT_SEARCH_BOX,
            SearchConfigJson.DEFAULT_KEY_SEARCH_BOX, HOTKEY_SETTINGS).apply(PREFIX);

    /** 取满手势开关（组合键在 FILL_COMBO 里，这里只管开不开） */
    public static final ConfigBooleanHotkeyed FILL_GESTURE = new ConfigBooleanHotkeyed(
            "fillGesture", SearchConfigJson.DEFAULT_FILL_GESTURE,
            SearchConfigJson.DEFAULT_KEY_FILL_GESTURE, HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 取满手势的<b>组合键</b>（默认 Ctrl + Alt + 左键，与 0.4.2 写死的行为一致）。
     *
     * <h2>为什么它是 {@code ConfigHotkey} 而不是又一个 {@code ConfigBooleanHotkeyed}</h2>
     * <p>它<b>不再是一个开关</b>：开关是 {@link #FILL_GESTURE}。这一条只回答
     * 「按住哪些键 + 点哪个鼠标键算取满」。玩家把它清空 = 这个手势彻底关掉
     * （空绑定 ⇒ {@link #isFillComboHeld} 恒 false）。</p>
     *
     * <h2>🔴 为什么判据不用 malilib 的 {@code isKeybindHeld()} / {@code isPressed()}</h2>
     * <p>{@code KeybindMulti#isKeybindHeld()} 的实现是
     * {@code pressed || (allowEmpty && keyCodes.isEmpty())} —— 我们开了
     * {@code allowEmpty}，所以<b>空绑定时它恒为 true</b>，直接拿来判「按住了组合键」
     * 会让「清空 = 关掉」变成「清空 = 任何点击都取满」。而 {@code isPressed()} 只反映
     * <b>按键那一刻</b>的边沿，鼠标点击与它不在同一拍上。所以这里自己逐键比对
     * （见 {@link #isFillComboHeld(int)}），完全绕开 malilib 的按键簿记。</p>
     *
     * <h2>🔴 为什么一个鼠标键 + 一串键盘键，而不是纯键盘热键</h2>
     * <p>「取满哪一格」只能由<b>点在哪</b>决定，纯热键没法表达目标格。所以语义是
     * <b>按住组合键 → 左键点那一格</b>，绑定里必须包含一个鼠标键。</p>
     */
    public static final ConfigHotkey FILL_COMBO = new ConfigHotkey(
            "fillCombo", SearchConfigJson.DEFAULT_KEY_FILL_COMBO, HOTKEY_SETTINGS).apply(PREFIX);

    /** 打开配置界面的热键（默认未绑定：没有热键也能从 Mod Menu / 其它 malilib 配置界面进来） */
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey(
            "openGui", SearchConfigJson.DEFAULT_KEY_OPEN_GUI, HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 全部条目，按配置界面「全部」页的顺序。
     *
     * <p>用 {@code List<IHotkey>} 一份列表同时喂两个消费者：界面
     * （{@code ConfigOptionWrapper.createFor} 要 {@code IConfigBase}，而
     * {@code IHotkey} → {@code IConfigValue} → {@code IConfigBase} 是子接口）与
     * malilib 的热键表（{@code addHotkeysForCategory} 要 {@code IHotkey}）。</p>
     */
    public static final List<IHotkey> OPTIONS = List.of(SEARCH_BOX, FILL_GESTURE, FILL_COMBO, OPEN_GUI);

    /**
     * 配置界面「通用」页：两个功能开关。
     *
     * <p>它们都是 {@code ConfigBooleanHotkeyed}，一格里同时有复选框和按键按钮 ——
     * 也就是说<b>开关的热键本来就摆在这一行</b>，不需要在「快捷键」页再列一遍。</p>
     */
    public static final List<IHotkey> GUI_FEATURES = List.of(SEARCH_BOX, FILL_GESTURE);

    /**
     * 配置界面「快捷键」页：纯热键条目（不带开关的）。
     *
     * <p>{@code FILL_COMBO} 排在最前面：它才是玩家最常改的那一条 —— 通用页的
     * 「取满」开关只回答开不开，<b>按哪个键</b>在这一页。</p>
     */
    public static final List<IHotkey> GUI_HOTKEYS = List.of(FILL_COMBO, OPEN_GUI);

    /** 给 malilib 的 {@code ConfigManager} 用（它会自己 new 一个；本类只有静态状态） */
    public SearchConfig() {
    }

    // ------------------------------------------------------------------ 磁盘

    /**
     * 配置文件路径：{@code .minecraft/config/neko-client.json}。
     *
     * <p>刻意不做成静态常量：{@code FabricLoader.getInstance()} 在「没启动游戏」的
     * JVM 里是 null，静态常量会让整个类一加载就 NPE，纯逻辑那一层也就跟着测不了。</p>
     */
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(SearchConfigJson.FILE_NAME);
    }

    /**
     * 从磁盘读进 malilib 配置对象（文件不存在就地写一份模板）。
     *
     * <p>调用点：客户端初始化、每次 {@code JOIN}、以及 malilib 的
     * {@code ConfigManager.loadAllConfigs()}。</p>
     */
    public static void loadFromFile() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            saveToFile();
            return;
        }
        try {
            SearchConfigJson.Values values =
                    SearchConfigJson.parse(Files.readString(path, StandardCharsets.UTF_8));
            // 布尔值与热键一起灌：malilib 的这个 setter 就是为「一对值」准备的
            SEARCH_BOX.setBooleanHotkeyValue(Pair.of(values.searchBox(), values.keySearchBox()));
            FILL_GESTURE.setBooleanHotkeyValue(Pair.of(values.fillGesture(), values.keyFillGesture()));
            FILL_COMBO.setHotkeyStringValue(values.keyFillCombo());
            OPEN_GUI.setHotkeyStringValue(values.keyOpenGui());
        } catch (IOException | RuntimeException broken) {
            // 文件坏了不能把功能一起带走：留着当前内存值，只报一行
            VoidWarehouseSearchClient.LOGGER.warn(
                    "[虚空仓库搜索] 配置文件读取失败，沿用当前设置：{}", path, broken);
        }
    }

    /** 把当前设置写回磁盘（模板含 {@code _readme}，方便玩家手改） */
    public static void saveToFile() {
        Pair<Boolean, String> box = SEARCH_BOX.getBooleanHotkeyValue();
        Pair<Boolean, String> fill = FILL_GESTURE.getBooleanHotkeyValue();
        String text = SearchConfigJson.render(new SearchConfigJson.Values(
                box.getLeft(), fill.getLeft(),
                box.getRight(), fill.getRight(),
                FILL_COMBO.getHotkeyStringValue(),
                OPEN_GUI.getHotkeyStringValue()));

        Path path = file();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, text, StandardCharsets.UTF_8);
        } catch (IOException failed) {
            VoidWarehouseSearchClient.LOGGER.warn(
                    "[虚空仓库搜索] 配置文件写入失败（设置只在本次游戏内有效）：{}", path, failed);
        }
    }

    // ------------------------------------------------------------------ IConfigHandler

    @Override
    public void load() {
        loadFromFile();
    }

    @Override
    public void save() {
        saveToFile();
    }

    // ------------------------------------------------------------------ 开关

    /** 容器界面里的搜索框（关闭后连 hello / catalog 都不再发，除非取满手势还需要） */
    public static boolean isSearchBoxEnabled() {
        return SEARCH_BOX.getBooleanValue();
    }

    /** 取满手势开关（关闭后退回原版「取 1 个」，界面上没有任何残迹） */
    public static boolean isFillGestureEnabled() {
        return FILL_GESTURE.getBooleanValue();
    }

    /**
     * 取满组合键的可读形式，<b>只给日志用</b>（未绑定时返回「未绑定」而不是空串）。
     *
     * <p>为什么要专门打印：玩家报「取满没反应」时，第一件事就是分辨
     * 「组合键没读到」和「组合键读到了但判据不对」。这一行在进服日志里写死，
     * 比让玩家去翻 json 快得多。</p>
     */
    public static String fillComboDisplay() {
        String text = FILL_COMBO.getKeybind().getKeysDisplayString();
        return text.isEmpty() ? "未绑定" : text;
    }

    /**
     * 这一下鼠标点击是不是「取满组合键」—— 是就返回 true，调用方据此把点击变成取满。
     *
     * <p>规则本体在 {@link FillCombo#matches}（纯数据、可单测）；这里只做两件事：
     * 从 malilib 拿到玩家绑的键码、把「键盘键按着没有」接到
     * {@code KeybindMulti.isKeyDown}（内部走 GLFW 实时状态，与「这一拍按下了什么」无关）。
     * <b>为什么不用 malilib 的 isKeybindHeld()</b> 见 {@link FillCombo} 的类注释。</p>
     *
     * @param mouseButton 原版 {@code MouseButtonEvent#button()}（26.3 是 1 基，其余 0 基）
     * @return 未绑定（玩家清空了）⇒ 恒 false，手势彻底关闭
     */
    public static boolean isFillComboHeld(int mouseButton) {
        return FillCombo.matches(FILL_COMBO.getKeybind().getKeys(), mouseButton,
                KeybindMulti::isKeyDown);
    }

    /**
     * 两个功能都关了 ⇒ 连目录都不要请求。
     *
     * <p>搜索框与取满手势共用同一条 hello/catalog 通道（取满要靠 catalog 才能确认
     * 「服务端认这个界面」），所以只要还有一个开着，就必须照常握手。</p>
     */
    public static boolean anyFeatureEnabled() {
        return isSearchBoxEnabled() || isFillGestureEnabled();
    }
}
