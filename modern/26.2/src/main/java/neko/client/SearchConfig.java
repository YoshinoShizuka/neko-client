package neko.client;

import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigBooleanHotkeyed;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.IHotkey;
import fi.dy.masa.malilib.hotkeys.IKeybind;
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
 * 两个功能开关 + 一个聊天静音开关 + 四个热键，<b>全部由 malilib 持有</b>（照 MiniHUD 的做法）。
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
 * 「malilib 配置对象 ↔ 那八个字段」的搬运，并实现 {@link IConfigHandler}
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
     * 静音虚空仓库的聊天提示（0.5.2 新增）。
     *
     * <p>这不是「功能开关」而是<b>显示偏好</b>：它不参与
     * {@link #anyFeatureEnabled()} 的握手判定 —— 只开静音时既不建搜索框，
     * 也不向服务端请求目录，纯粹在收到系统聊天包时把命中
     * {@link VoidWarehouseChatFilter} 的那几条丢掉。</p>
     *
     * <p>默认 <b>false</b>：升级后老玩家仍照常看到提示，避免「服务端不说话了」
     * 的困惑。热键留空（与其它开关一致，不抢字母键）。</p>
     */
    public static final ConfigBooleanHotkeyed SILENCE_CHAT = new ConfigBooleanHotkeyed(
            "silenceChat", SearchConfigJson.DEFAULT_SILENCE_CHAT,
            "", HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 投影容器填充（0.5.4）：右键投影里的容器 → 打开后按投影内容填充。
     * 默认 false；这条不改变原版右键语义，除非玩家主动打开。
     */
    public static final ConfigBooleanHotkeyed PROJECTION_FILL = new ConfigBooleanHotkeyed(
            "projectionFill", SearchConfigJson.DEFAULT_PROJECTION_FILL,
            "", HOTKEY_SETTINGS).apply(PREFIX);


    /**
     * 取满手势的<b>组合键</b>（0.4.7 起默认 Ctrl + Alt + 右键；只影响新生成的配置，
     * 已有的 {@code keyFillCombo} 不会被改写。想用左键就把鼠标键改成 {@code BUTTON_1}）。
     *
     * <h2>为什么它是 {@code ConfigHotkey} 而不是又一个 {@code ConfigBooleanHotkeyed}</h2>
     * <p>它<b>不再是一个开关</b>：开关是 {@link #FILL_GESTURE}。这一条只回答
     * 「按住哪些键 + 点哪个鼠标键算取满」。玩家把它清空 = 这个手势彻底关掉
     * （空绑定 ⇒ {@link #isFillComboHeld} 恒 false）。</p>
     *
     * <h2>🔴 为什么判据不用 malilib 的任何「按着没有」</h2>
     * <p>0.4.3 用过 {@code KeybindMulti.isKeyDown}，26.3 真机上恒 false（把 SDLK 当
     * SDL scancode 索引，读到缓冲区外）；{@code isKeybindHeld()} 也一起坏 ——
     * 它的 {@code pressed} 每帧被 {@code reCheckPressedKeys()} 用同一个坏 API 重算。
     * 现在修饰键由点击事件自带的掩码回答，推导见 {@link FillCombo} 类注释。
     * 另外注意 {@code allowEmpty} 开着，{@code isKeybindHeld()} 在<b>空绑定时恒为
     * true</b>，就算它没坏也不能单独当「按住了组合键」用。</p>
     *
     * <h2>🔴 为什么一个鼠标键 + 一串修饰键，而不是纯键盘热键</h2>
     * <p>「取满哪一格」只能由<b>点在哪</b>决定，纯热键没法表达目标格。所以语义是
     * <b>按住组合键 → 左键点那一格</b>，绑定里必须包含一个鼠标键。</p>
     */
    public static final ConfigHotkey FILL_COMBO = new ConfigHotkey(
            "fillCombo", SearchConfigJson.DEFAULT_KEY_FILL_COMBO, HOTKEY_SETTINGS).apply(PREFIX);

    /** 打开配置界面的热键（默认未绑定：没有热键也能从 Mod Menu / 其它 malilib 配置界面进来） */
    public static final ConfigHotkey OPEN_GUI = new ConfigHotkey(
            "openGui", SearchConfigJson.DEFAULT_KEY_OPEN_GUI, HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 打开「配方本」界面的热键（默认未绑定）。
     *
     * <p>为什么不塞个常驻按钮进仓库界面：那面框只有 176 宽，搜索框已经占掉 140，
     * 再挤一个按钮就要改所有玩家的习惯布局。热键是 malilib 的原生能力，
     * 而且和「打开配置界面」放在同一页里，玩家找得到。</p>
     */
    public static final ConfigHotkey OPEN_RECIPES = new ConfigHotkey(
            "openRecipes", SearchConfigJson.DEFAULT_KEY_OPEN_RECIPES, HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 打开「容器填充物合计」界面的热键（0.5.6，默认未绑定）。
     *
     * <p>这是这个功能唯一的入口：整张原理图所有容器的填充物合计，
     * 对照背包 + 虚空仓库看还缺多少。不依赖是否开着某个容器，
     * 也不依赖是否正在填充。</p>
     */
    public static final ConfigHotkey OPEN_CONTAINER_MATERIALS = new ConfigHotkey(
            "openContainerMaterials", SearchConfigJson.DEFAULT_KEY_OPEN_CONTAINER_MATERIALS,
            HOTKEY_SETTINGS).apply(PREFIX);

    /**
     * 全部条目，按配置界面「全部」页的顺序。
     *
     * <p>用 {@code List<IHotkey>} 一份列表同时喂两个消费者：界面
     * （{@code ConfigOptionWrapper.createFor} 要 {@code IConfigBase}，而
     * {@code IHotkey} → {@code IConfigValue} → {@code IConfigBase} 是子接口）与
     * malilib 的热键表（{@code addHotkeysForCategory} 要 {@code IHotkey}）。</p>
     */
    public static final List<IHotkey> OPTIONS =
            List.of(SEARCH_BOX, FILL_GESTURE, SILENCE_CHAT, PROJECTION_FILL,
                    FILL_COMBO, OPEN_GUI, OPEN_RECIPES, OPEN_CONTAINER_MATERIALS);

    /**
     * 配置界面「通用」页：三个开关（第三个是聊天静音）。
     *
     * <p>它们都是 {@code ConfigBooleanHotkeyed}，一格里同时有复选框和按键按钮 ——
     * 也就是说<b>开关的热键本来就摆在这一行</b>，不需要在「快捷键」页再列一遍。</p>
     */
    public static final List<IHotkey> GUI_FEATURES =
            List.of(SEARCH_BOX, FILL_GESTURE, SILENCE_CHAT, PROJECTION_FILL);

    /**
     * 配置界面「快捷键」页：纯热键条目（不带开关的）。
     *
     * <p>{@code FILL_COMBO} 排在最前面：它才是玩家最常改的那一条 —— 通用页的
     * 「取满」开关只回答开不开，<b>按哪个键</b>在这一页。</p>
     */
    public static final List<IHotkey> GUI_HOTKEYS =
            List.of(FILL_COMBO, OPEN_GUI, OPEN_RECIPES, OPEN_CONTAINER_MATERIALS);

    /**
     * 🔴 把开关热键的「切换提示」也翻成中文。
     *
     * <p>malilib 的 {@code ConfigBooleanHotkeyed} 自带一条「已切换 X 为 开/关」提示，
     * 它读的是 {@code getPrettyName()}，默认会把内部名按驼峰拆成 {@code Projection Fill}，
     * 与配置界面里翻译好的「投影容器填充」对不上（真机截图里就是半英文）。
     * 设成带 {@code prettyName.} 段的翻译键后，malilib 自己会查我们的语言文件。
     * 五版本 malilib 的 {@code setPrettyName} 已 javap 核对存在。</p>
     */
    static {
        SEARCH_BOX.setPrettyName(PREFIX + ".prettyName.searchBox");
        FILL_GESTURE.setPrettyName(PREFIX + ".prettyName.fillGesture");
        SILENCE_CHAT.setPrettyName(PREFIX + ".prettyName.silenceChat");
        PROJECTION_FILL.setPrettyName(PREFIX + ".prettyName.projectionFill");
    }

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
            SILENCE_CHAT.setBooleanValue(values.silenceChat());
            PROJECTION_FILL.setBooleanValue(values.projectionFill());
            FILL_COMBO.setHotkeyStringValue(values.keyFillCombo());
            OPEN_GUI.setHotkeyStringValue(values.keyOpenGui());
            OPEN_RECIPES.setHotkeyStringValue(values.keyOpenRecipes());
            OPEN_CONTAINER_MATERIALS.setHotkeyStringValue(values.keyOpenContainerMaterials());
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
                box.getLeft(), fill.getLeft(), SILENCE_CHAT.getBooleanValue(),
                PROJECTION_FILL.getBooleanValue(),
                box.getRight(), fill.getRight(),
                FILL_COMBO.getHotkeyStringValue(),
                OPEN_GUI.getHotkeyStringValue(),
                OPEN_RECIPES.getHotkeyStringValue(),
                OPEN_CONTAINER_MATERIALS.getHotkeyStringValue()));

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

    /** 聊天静音开关（只影响这一台客户端的显示，不改动服务端行为） */
    public static boolean isChatSilenced() {
        return SILENCE_CHAT.getBooleanValue();
    }

    /** 投影容器填充是否启用（默认 false） */
    public static boolean isProjectionFillEnabled() {
        return PROJECTION_FILL.getBooleanValue();
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

    // ------------------------------------------------------------------ 诊断日志

    /**
     * 判据 / 状态类日志：前 40 行全打，之后每秒最多一行。
     *
     * <p>0.4.4 的「首击只打一次」是个设计错误：真机上那一击抓到的是右键，
     * 之后玩家真正按的 Ctrl+Alt+左键一个字都没留下，日志看起来「模组没反应」，
     * 实际上只是没记录。<b>只打一次的日志等于没有日志</b> —— 但也不能让连点把
     * 日志刷爆，所以是有限的 burst + 之后的节流。</p>
     */
    private static int traceCount;
    private static long lastTraceAt;

    public static void trace(String format, Object... args) {
        long now = System.currentTimeMillis();
        if (traceCount >= 40 && now - lastTraceAt < 1000L) {
            return;
        }
        traceCount++;
        lastTraceAt = now;
        VoidWarehouseSearchClient.LOGGER.info("[虚空仓库搜索] " + format, args);
    }

    /**
     * <b>链路结果</b>日志：与 {@link #trace} <b>各用一份预算</b>。
     *
     * <p>0.4.5 真机上出现过「判据命中了、`已取出` 也出来了，但 `取满发包成功`
     * 那行没打」—— 因为整条链路共用一个 40 行 burst + 1/s 限流，判据行把
     * 结果行挤掉了。<b>日志分级不分开，等于关键结果随时会消失</b>，排障时
     * 又只能靠猜。发包成功 / 失败、中止原因、链路结果一律走这里。</p>
     */
    private static int auditCount;
    private static long lastAuditAt;

    public static void audit(String format, Object... args) {
        long now = System.currentTimeMillis();
        if (auditCount >= 200 && now - lastAuditAt < 1000L) {
            return;
        }
        auditCount++;
        lastAuditAt = now;
        VoidWarehouseSearchClient.LOGGER.info("[虚空仓库搜索] " + format, args);
    }

    /**
     * 这一下鼠标点击是不是「取满组合键」—— 是就返回 true，调用方据此把点击变成取满。
     *
     * <p>规则本体在 {@link FillCombo#matches}（纯数据、可单测）；这里只负责
     * 从 malilib 把「玩家绑了哪些键」捞出来，再把它和调用方给的修饰键状态拼起来。</p>
     *
     * <h2>🔴 0.4.6：绑什么键就是什么键</h2>
     * <p>0.4.5 在精确判否之后还挂了一条「按钮号差 ≤2 也算」的容错回退，
     * 真机上玩家绑 Ctrl+Alt+左键，却大面积被 Ctrl+Alt+右键取满。这里已<b>完全删除</b>：
     * 只有 {@code FillCombo.matches}，一条判据、零回退。想用右键就把
     * {@code keyFillCombo} 的鼠标键显式改成 {@code BUTTON_2} / {@code MOUSE_RIGHT}。</p>
     *
     * <h2>🔴 为什么「未命中」要写出原因</h2>
     * <p>按钮号不符与修饰键没按全，给玩家的提示完全不同：前者该改绑定，后者该改按法。
     * 日志会直接写「点的是键码 X，绑定的是 Y」，不再让玩家对着一个 `false` 猜。</p>
     *
     * <h2>🔴 为什么修饰键是<b>入参</b>，而不是在这里自己去问</h2>
     * <p>0.4.3 在这里调 {@code KeybindMulti.isKeyDown} 逐键问「按着没有」，真机上
     * <b>一次都没命中且零异常</b>：26.x 的 malilib 把 SDLK（LEFT_CONTROL =
     * 1073742048）当成 SDL 的 scancode（0..511）去索引键盘状态缓冲区，读到的是
     * 缓冲区外的一兆字节处，恰好是 0。{@code isKeybindHeld()} 同样不可用
     * （{@code PRESSED_KEYS} 每帧被同一个坏 API 清理，按住的 Ctrl 会被当成已松开）。
     * 点击事件自己带着<b>点击那一瞬间</b>的修饰键掩码，那份数据一定是对的 ——
     * 原版「Shift 点 = 整组快速移动」读的就是它。完整推导见 {@link FillCombo} 类注释。</p>
     *
     * @param mouseButton 原版 {@code MouseButtonEvent#button()}（26.3 是 1 基，其余 0 基）
     * @param shift       {@code event.hasShiftDown()}
     * @param control     {@code event.hasControlDown()}
     * @param alt         {@code event.hasAltDown()}
     * @return 组合键不可用（玩家清空了 / 绑了字母键）⇒ 恒 false
     */
    public static boolean isFillComboHeld(int mouseButton, boolean shift, boolean control, boolean alt) {
        FillCombo.Combo combo = fillCombo();
        // 🔴 唯一判据。任何「精确判否就再放宽一次」的回退都不许回来 —— 见 0.4.5 事故。
        boolean matched = FillCombo.matches(combo, mouseButton, shift, control, alt);
        // 带修饰键的点击、或者已经命中的点击，都留一行原话。
        if ((shift || control || alt) || matched) {
            int clickedCode = mouseButton - FillCombo.MOUSE_KEY_OFFSET;
            String verdict;
            if (!combo.usable()) {
                verdict = "未命中：组合键不可用（" + combo.problem() + "）";
            } else if (combo.mouseCode() != clickedCode) {
                verdict = "未命中：点的是键码 " + clickedCode + "，绑定的是 " + combo.mouseCode()
                        + "（绑什么键就是什么键；想用这一击请把 keyFillCombo 的鼠标键改成对应的 BUTTON_n）";
            } else if (!FillCombo.modifiersMatch(combo, shift, control, alt)) {
                verdict = "未命中：绑定的修饰键没按全";
            } else {
                verdict = "命中（精确）";
            }
            trace("取满判据：按钮={} → 键码={} 绑定鼠标键={} 组合键={}（{}） "
                            + "Shift={} Ctrl={} Alt={} ⇒ {}",
                    mouseButton, clickedCode, combo.mouseCode(), fillComboDisplay(),
                    combo.usable() ? "可用" : "不可用：" + combo.problem(),
                    shift, control, alt, verdict);
        }
        return matched;
    }

    /**
     * 组合键现在能不能用 —— 只给进服那一行日志用。
     *
     * <p>为什么要打：玩家报「取满没反应」时，第一件事是分辨「绑定里根本没有鼠标键 /
     * 绑了字母键所以永远不生效」和「绑对了但没按对」。上一轮就是因为没有这一行，
     * 只能靠猜。</p>
     */
    public static String fillComboStatus() {
        FillCombo.Combo combo = fillCombo();
        return combo.usable() ? "可用" : "不可用：" + combo.problem();
    }

    /** 从 malilib 的 {@code IKeybind} 拿「键码 + 键名」，交给纯判据解析 */
    private static FillCombo.Combo fillCombo() {
        IKeybind keybind = FILL_COMBO.getKeybind();
        return FillCombo.parse(keybind.getKeys(), FillCombo.namesOf(keybind.getKeysDisplayString()));
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
