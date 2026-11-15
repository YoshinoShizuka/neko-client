package neko.client;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.lang3.tuple.Pair;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * 两个功能的开关 + 三个热键，<b>全部由 malilib 持有</b>（照 MiniHUD 的做法）。
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

    /** Ctrl + Alt + 左键 取满手势开关（热键默认未绑定） */
    public static final ConfigBooleanHotkeyed FILL_GESTURE = new ConfigBooleanHotkeyed(
            "fillGesture", SearchConfigJson.DEFAULT_FILL_GESTURE,
            SearchConfigJson.DEFAULT_KEY_FILL_GESTURE, HOTKEY_SETTINGS).apply(PREFIX);

    /** 打开配置界面的热键（默认未绑定：没有热键也能从 Mod Menu / 其它 malilib 配置界面进来） */
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey(
            "openGui", SearchConfigJson.DEFAULT_KEY_OPEN_GUI, HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 配置界面里按顺序列出的全部条目。
     *
     * <p>用 {@code List<IHotkey>} 一份列表同时喂两个消费者：界面
     * （{@code ConfigOptionWrapper.createFor} 要 {@code IConfigBase}，而
     * {@code IHotkey} 是它的子接口）与 malilib 的热键表
     * （{@code addHotkeysForCategory} 要 {@code IHotkey}）。两份列表会漂，一份不会。</p>
     */
    public static final List<IHotkey> OPTIONS = List.of(SEARCH_BOX, FILL_GESTURE, OPEN_GUI);

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

    /** Ctrl + Alt + 左键 取满手势（关闭后退回原版「取 1 个」，界面上没有任何残迹） */
    public static boolean isFillGestureEnabled() {
        return FILL_GESTURE.getBooleanValue();
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
