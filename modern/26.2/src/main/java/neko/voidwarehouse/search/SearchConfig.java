package neko.voidwarehouse.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 两个功能各自的独立开关，落在 {@code config/neko-voidwarehouse-search.json}。
 *
 * <h2>为什么不用 Gson 直接序列化一个配置类</h2>
 * <p>JSON 没有注释，而这文件是给玩家手改的。「改完要不要重启」「快捷键去哪改」
 * 这类问题写在 README 里没人看 —— 所以这里<b>手写模板</b>，把说明做成
 * {@code _readme} 字段一起落盘；读的时候用 {@link JsonParser} 宽松解析，
 * 缺字段一律回落到内置默认值（老版本配置文件、玩家删了一行，都不会炸）。</p>
 *
 * <h2>写盘时机</h2>
 * <p><b>每次开关一变就立刻写</b>，不等退出游戏：玩家按完热键就去打怪了，
 * 崩一次客户端不该把开关吞掉。写盘失败只记日志、不影响当前内存状态 ——
 * 开关是「本次会话有效」的，落盘只是尽力而为。</p>
 *
 * <h2>生效时机</h2>
 * <ul>
 *   <li><b>热键切换</b>：立刻生效（当前界面也会就地刷新，见 {@link SearchScreenAccess}）；</li>
 *   <li><b>手改文件</b>：进一次世界（{@code JOIN} 时重读）或重开游戏；</li>
 *   <li><b>打开容器界面</b>：每次 {@code init} 都读当前内存值，所以改完文件重开界面也生效。</li>
 * </ul>
 *
 * <h2>🔴 为什么把「读 JSON」和「碰磁盘」拆开</h2>
 * <p>{@link #parse(String)} / {@link #render(boolean, boolean)} 是<b>纯函数</b>，
 * 不碰 {@link FabricLoader}、不碰文件系统 —— 于是它们能在 JUnit 里直接跑
 * （{@code :modern:26.3:test}，不启动游戏）。这不是为了测试而测试：模板是手写的
 * 文本块，写错一个引号就会让所有人「配置改了没反应」，而这种错误只有真解析一遍才抓得到。
 * 磁盘那半（{@link #load()} / {@link #save()}）只负责按 {@link #file()} 取路径、
 * 读写文本、打日志。</p>
 */
public final class SearchConfig {

    /** 配置文件在 {@code config/} 下的文件名 */
    private static final String FILE_NAME = "neko-voidwarehouse-search.json";

    /** 内置默认值：两个功能都开（装了就按 0.2.0 之前的行为跑） */
    private static final boolean DEFAULT_SEARCH_BOX = true;
    private static final boolean DEFAULT_FILL_GESTURE = true;

    private static boolean searchBox = DEFAULT_SEARCH_BOX;
    private static boolean fillGesture = DEFAULT_FILL_GESTURE;

    private SearchConfig() {
    }

    /**
     * 解析结果（纯数据）。
     *
     * <p>用 record 而不是两个 {@code boolean[]}：{@code Values[0]} 这种写法
     * 在断言失败时的报错信息会是一串 {@code [Z@1b6d3586}，看不出是哪个开关。</p>
     */
    record Values(boolean searchBox, boolean fillGesture) {
    }

    // ------------------------------------------------------------------ 纯逻辑（可脱离游戏测试）

    /**
     * 把配置 JSON 解析成两个开关。<b>不碰磁盘</b>。
     *
     * @throws IllegalArgumentException 顶层不是 JSON 对象（语法错误由 Gson 抛
     *                                  {@code JsonSyntaxException}，同样是 RuntimeException）
     */
    static Values parse(String text) {
        JsonElement root = JsonParser.parseString(text);
        if (!root.isJsonObject()) {
            throw new IllegalArgumentException("顶层不是 JSON 对象");
        }
        JsonObject json = root.getAsJsonObject();
        return new Values(
                readBoolean(json, "searchBox", DEFAULT_SEARCH_BOX),
                readBoolean(json, "fillGesture", DEFAULT_FILL_GESTURE));
    }

    /**
     * 生成落盘的文本（含 {@code _readme} 说明）。<b>不碰磁盘</b>。
     *
     * <p>字段顺序固定、两个空格缩进：玩家手改时代码对齐，diff 也干净。</p>
     */
    static String render(boolean searchBox, boolean fillGesture) {
        return """
                {
                  "_readme": "两个功能的独立开关。改完保存后重进一次世界即生效（热键切换是立刻生效）。",
                  "_readme_keys": "快捷键不在这里改：选项 → 控制 → 按键绑定 → 虚空仓库。开关状态在游戏内按热键切换时会立刻写回本文件。",
                  "searchBox": %s,
                  "fillGesture": %s
                }
                """.formatted(searchBox, fillGesture);
    }

    /** 缺字段、类型不对（例如手改成字符串 {@code "false"}）一律回落到默认值 */
    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return value.getAsBoolean();
    }

    // ------------------------------------------------------------------ 磁盘

    /**
     * 配置文件路径：{@code .minecraft/config/neko-voidwarehouse-search.json}。
     *
     * <p>🔴 <b>刻意不做成静态常量</b>：{@code FabricLoader.getInstance()} 在
     * 「没启动游戏」的 JVM 里是 null，静态常量会让整个类一加载就 NPE ——
     * 那样纯函数也测不了。放到方法里，只有真读写磁盘时才会碰它。</p>
     */
    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
    }

    /** 从磁盘读（文件不存在就地写一份模板）。{@code JOIN} 与客户端初始化各调一次 */
    public static void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            save();
            return;
        }
        try {
            Values values = parse(Files.readString(path, StandardCharsets.UTF_8));
            searchBox = values.searchBox();
            fillGesture = values.fillGesture();
        } catch (IOException | RuntimeException broken) {
            // 文件坏了不能把功能一起带走：留着上次的内存值（首次加载即默认值），只报一行
            VoidWarehouseSearchClient.LOGGER.warn("[虚空仓库搜索] 配置文件读取失败，沿用当前开关：{}", path, broken);
        }
    }

    /** 把当前开关写回磁盘（模板含 {@code _readme}，方便玩家手改） */
    public static void save() {
        Path path = file();
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Files.writeString(path, render(searchBox, fillGesture), StandardCharsets.UTF_8);
        } catch (IOException failed) {
            VoidWarehouseSearchClient.LOGGER.warn("[虚空仓库搜索] 配置文件写入失败（开关只在本次游戏内有效）：{}", path, failed);
        }
    }

    // ------------------------------------------------------------------ 开关

    /** 容器界面里的搜索框（关闭后连 hello / catalog 都不再发，除非取满手势还需要） */
    public static boolean isSearchBoxEnabled() {
        return searchBox;
    }

    /** Ctrl + Alt + 左键 取满手势（关闭后退回原版「取 1 个」，界面上没有任何残迹） */
    public static boolean isFillGestureEnabled() {
        return fillGesture;
    }

    /** @return 切换后的状态 */
    public static boolean toggleSearchBox() {
        searchBox = !searchBox;
        save();
        return searchBox;
    }

    /** @return 切换后的状态 */
    public static boolean toggleFillGesture() {
        fillGesture = !fillGesture;
        save();
        return fillGesture;
    }

    /**
     * 两个功能都关了 ⇒ 连目录都不要请求。
     *
     * <p>搜索框与取满手势共用同一条 hello/catalog 通道（取满要靠 catalog 才能确认
     * 「服务端认这个界面」），所以只要还有一个开着，就必须照常握手。</p>
     */
    public static boolean anyFeatureEnabled() {
        return searchBox || fillGesture;
    }
}
