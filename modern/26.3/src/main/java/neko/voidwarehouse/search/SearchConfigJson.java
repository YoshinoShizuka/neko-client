package neko.voidwarehouse.search;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 配置文件的<b>纯文本层</b>：一段 JSON 文本 ↔ 五个字段。
 *
 * <h2>为什么单独拆一层</h2>
 * <p>从 0.3.0 起配置对象改由 malilib 提供（{@code ConfigBooleanHotkeyed} /
 * {@code ConfigHotkey}），那些类在静态初始化里就摸 MC 的 {@code Identifier}、
 * malilib 的 {@code StringUtils} —— 在「没启动游戏」的 JVM 里根本加载不了，
 * 于是<b>它们不能出现在单测能碰到的路径上</b>。</p>
 *
 * <p>所以文件格式这一层单独拎出来：本类<b>不 import 任何 malilib / MC 类型</b>
 * （只用 Gson），读写都是纯函数，JUnit 里直接跑。写错一个引号、少一个字段，
 * 都是「所有人配置改了没反应」级别的故障，只有真解析一遍才抓得住。</p>
 *
 * <h2>为什么手写模板而不是 Gson 序列化</h2>
 * <p>JSON 没有注释，而这文件是给玩家手改的。「改完要不要重启」「热键去哪改」
 * 写在 README 里没人看 —— 那就把说明做成 {@code _readme} 字段一起落盘。
 * 读的时候一律宽松：缺字段、类型不对、键名拼错，全部回落到内置默认值。</p>
 */
final class SearchConfigJson {

    /** 配置文件在 {@code config/} 下的文件名 */
    static final String FILE_NAME = "neko-voidwarehouse-search.json";

    /** 内置默认值：两个功能都开、三个热键分别是 H / J / V（原版默认键位里都空着） */
    static final boolean DEFAULT_SEARCH_BOX = true;
    static final boolean DEFAULT_FILL_GESTURE = true;
    static final String DEFAULT_KEY_SEARCH_BOX = "H";
    static final String DEFAULT_KEY_FILL_GESTURE = "J";
    static final String DEFAULT_KEY_OPEN_GUI = "V";

    private SearchConfigJson() {
    }

    /**
     * 解析出来的一整套配置（纯数据）。
     *
     * <p>三个热键用 malilib 的<b>存储串</b>（{@code "H"}、{@code "LEFT_CONTROL,LEFT_ALT"}
     * 这种），不是键码整数：存储串跨版本稳定，而键码在 GLFW / InputConstants 之间
     * 换算过一次，落盘存整数等于把版本差异钉进玩家档案里。</p>
     */
    record Values(boolean searchBox,
                  boolean fillGesture,
                  String keySearchBox,
                  String keyFillGesture,
                  String keyOpenGui) {

        static Values defaults() {
            return new Values(DEFAULT_SEARCH_BOX, DEFAULT_FILL_GESTURE,
                    DEFAULT_KEY_SEARCH_BOX, DEFAULT_KEY_FILL_GESTURE, DEFAULT_KEY_OPEN_GUI);
        }

        /**
         * 只要还有一个功能开着，就得跟服务端握手（搜索框与取满共用 hello/catalog：
         * 取满要靠 catalog 才能确认「服务端认这个界面」）。
         *
         * <p>判定放在这层是为了能单测 —— 「都关才不发 hello」这种判据最怕写成恒真。</p>
         */
        boolean anyEnabled() {
            return this.searchBox || this.fillGesture;
        }
    }

    /**
     * 把配置 JSON 解析成一整套配置。<b>不碰磁盘</b>。
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
                readBoolean(json, "fillGesture", DEFAULT_FILL_GESTURE),
                readKey(json, "keySearchBox", DEFAULT_KEY_SEARCH_BOX),
                readKey(json, "keyFillGesture", DEFAULT_KEY_FILL_GESTURE),
                readKey(json, "keyOpenGui", DEFAULT_KEY_OPEN_GUI));
    }

    /**
     * 生成落盘的文本（含 {@code _readme} 说明）。<b>不碰磁盘</b>。
     *
     * <p>字段顺序固定、两个空格缩进：玩家手改时代码对齐，diff 也干净。</p>
     */
    static String render(Values values) {
        return """
                {
                  "_readme": "两个功能的开关 + 三个热键。改完保存后重进一次世界即生效（游戏内按热键切换是立刻生效的）。",
                  "_readme_keys": "热键的写法是 malilib 的键名：单个键写 H / J，组合键写 LEFT_CONTROL,LEFT_ALT 这样用逗号连起来。游戏内按 V 打开配置界面也能改。",
                  "searchBox": %s,
                  "fillGesture": %s,
                  "keySearchBox": "%s",
                  "keyFillGesture": "%s",
                  "keyOpenGui": "%s"
                }
                """.formatted(values.searchBox(), values.fillGesture(),
                values.keySearchBox(), values.keyFillGesture(), values.keyOpenGui());
    }

    /** 缺字段、类型不对（例如手改成字符串 {@code "false"}）一律回落到默认值 */
    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return value.getAsBoolean();
    }

    /** 热键串：必须是「非空白的字符串」才认，空串 / 数字 / 数组一律回落到默认键 */
    private static String readKey(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? fallback : text;
    }
}
