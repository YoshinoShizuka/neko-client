package neko.client;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * 配置文件的<b>纯文本层</b>：一段 JSON 文本 ↔ 六个字段。
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
    static final String FILE_NAME = "neko-client.json";

    /**
     * 内置默认值：两个功能都开、<b>三条开关热键默认全部留空（= 未绑定）</b>。
     *
     * <p>刻意不给默认键：H / J / V 这类单字母键在整合包里几乎必然与别的模组撞车，
     * 玩家按下去先触发对方的概率远高于触发我们。留空的代价是「第一次要自己绑一个」，
     * 但不会有「偷偷占了一个键」的投诉 —— 空绑定是合法的
     * （{@code KeybindSettings} 里 {@code allowEmpty=true}）。</p>
     */
    static final boolean DEFAULT_SEARCH_BOX = true;
    static final boolean DEFAULT_FILL_GESTURE = true;
    static final String DEFAULT_KEY_SEARCH_BOX = "";
    static final String DEFAULT_KEY_FILL_GESTURE = "";
    static final String DEFAULT_KEY_OPEN_GUI = "";

    /** 打开「配方本」界面的热键（默认未绑定，与其它开关热键同一条理由：别抢字母键） */
    static final String DEFAULT_KEY_OPEN_RECIPES = "";

    /**
     * 🔴 取满组合键<b>刻意给了默认值</b>（与上面三条相反，这是唯一例外）。
     *
     * <p>0.4.3 之前这个手势是写死的 Ctrl+Alt+左键。这里若也留空，老玩家升级后
     * 会看到「模组还在、开关还是开、可是按下去什么都不发生」—— 等于把一次
     * 「可配置化」做成了功能删除。<b>所以默认值必须是能用的那一击</b>，玩家想改就改、
     * 想彻底关掉就把这一行清空（空串 = 手势关闭）。</p>
     *
     * <p>🔴 <b>0.4.7 起默认改成右键（{@code BUTTON_2}）</b>：真机复测确认
     * 「左键绑定不生效、右键绑定有效」（0.4.6 日志里左键那三次都卡在
     * `clicked (470,275)/(472,290)/(421,345)` 落在玩家背包/快捷栏，不是内容格；
     * 但按机主决定，默认直接给能用的那一击）。默认值只影响<b>新生成的配置</b>，
     * 已有 `keyFillCombo` 的老配置不会被改写。</p>
     *
     * <p>键名是 malilib 的存储串，{@code BUTTON_1} = 鼠标左键、{@code BUTTON_2} = 右键、
     * {@code BUTTON_3} = 中键（26.3 也可写 {@code MOUSE_LEFT} / {@code MOUSE_RIGHT} /
     * {@code MOUSE_MIDDLE}）。这个<b>名字</b>在四个 malilib 版本里都一样，虽然内部键码不同
     * （26.3：左 -99 / 右 -97；26.2 / 26.1.x / 1.21.11：左 -100 / 右 -99）——
     * 名字由各自的 malilib 解释，所以<b>写字符串一份就够，不必按版本分叉</b>。</p>
     *
     * <p>判据严格匹配（0.4.6 起）：绑 {@code BUTTON_2} 就<b>只有右键</b>触发，
     * 左键 / 中键一律不认。想换回左键就把这一项改成 {@code BUTTON_1}；
     * 不存在任何按钮号容错。</p>
     */
    static final String DEFAULT_KEY_FILL_COMBO = "LEFT_CONTROL,LEFT_ALT,BUTTON_2";

    private SearchConfigJson() {
    }

    /**
     * 解析出来的一整套配置（纯数据）。
     *
     * <p>四个热键用 malilib 的<b>存储串</b>（{@code "H"}、{@code "LEFT_CONTROL,LEFT_ALT"}
     * 这种），不是键码整数：存储串跨版本稳定，而键码在 GLFW / InputConstants 之间
     * 换算过一次，落盘存整数等于把版本差异钉进玩家档案里。</p>
     */
    record Values(boolean searchBox,
                  boolean fillGesture,
                  String keySearchBox,
                  String keyFillGesture,
                  String keyFillCombo,
                  String keyOpenGui,
                  String keyOpenRecipes) {

        static Values defaults() {
            return new Values(DEFAULT_SEARCH_BOX, DEFAULT_FILL_GESTURE,
                    DEFAULT_KEY_SEARCH_BOX, DEFAULT_KEY_FILL_GESTURE,
                    DEFAULT_KEY_FILL_COMBO, DEFAULT_KEY_OPEN_GUI, DEFAULT_KEY_OPEN_RECIPES);
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
                readKeyKeepingEmpty(json, "keyFillCombo", DEFAULT_KEY_FILL_COMBO),
                readKey(json, "keyOpenGui", DEFAULT_KEY_OPEN_GUI),
                readKey(json, "keyOpenRecipes", DEFAULT_KEY_OPEN_RECIPES));
    }

    /**
     * 生成落盘的文本（含 {@code _readme} 说明）。<b>不碰磁盘</b>。
     *
     * <p>字段顺序固定、两个空格缩进：玩家手改时代码对齐，diff 也干净。</p>
     */
    static String render(Values values) {
        return """
                {
                  "_readme": "两个功能的开关 + 五个热键（多出来的那个是打开配方本）。改完保存后重进一次世界即生效（游戏内按热键切换是立刻生效的）。",
                  "_readme_keys": "开关热键默认全部留空（未绑定），需要自己绑一个：进游戏后按配置界面的热键，或在任意 malilib 模组的配置界面右上角下拉框里选本模组。写法是 malilib 的键名：单个键写 H，组合键写 LEFT_CONTROL,LEFT_ALT，鼠标键写 BUTTON_1。",
                  "_readme_fillCombo": "keyFillCombo 是「取满手势」的组合键，必须恰好包含一个鼠标键：BUTTON_1 = 左键、BUTTON_2 = 右键、BUTTON_3 = 中键（26.3 也可写 MOUSE_LEFT / MOUSE_RIGHT / MOUSE_MIDDLE）。0.4.7 起默认 LEFT_CONTROL,LEFT_ALT,BUTTON_2 = Ctrl+Alt+右键；想用左键就把它改成 BUTTON_1。判据严格匹配：绑右键时左键不会触发，反之亦然。只对前 5 行内容格生效（第 6 行是导航行，玩家背包/快捷栏不算）。写成空字符串 = 清空 = 关掉这个手势（开关开着也不响应）。",
                  "searchBox": %s,
                  "fillGesture": %s,
                  "keySearchBox": "%s",
                  "keyFillGesture": "%s",
                  "keyFillCombo": "%s",
                  "keyOpenGui": "%s",
                  "keyOpenRecipes": "%s"
                }
                """.formatted(values.searchBox(), values.fillGesture(),
                values.keySearchBox(), values.keyFillGesture(), values.keyFillCombo(),
                values.keyOpenGui(), values.keyOpenRecipes());
    }

    /** 缺字段、类型不对（例如手改成字符串 {@code "false"}）一律回落到默认值 */
    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            return fallback;
        }
        return value.getAsBoolean();
    }

    /** 热键串：必须是字符串才认（数字 / 数组 / null 一律回落）；空白串按「未绑定」处理 */
    private static String readKey(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        String text = value.getAsString().trim();
        return text.isEmpty() ? fallback : text;
    }

    /**
     * 和 {@link #readKey} 一样，但<b>「字段在、值是空串」原样保留为空</b>，只有
     * 「字段不在 / 类型不对」才回落。
     *
     * <p>🔴 这条区别是给 {@code keyFillCombo} 用的，别合并回去：它<b>默认非空</b>
     * （见 {@link #DEFAULT_KEY_FILL_COMBO}），玩家想关掉手势就得把它清空。
     * 若沿用 {@code readKey} 的「空 → 回落」，玩家清空后一重进世界默认组合键又
     * 长回来了 —— 表现为「关不掉」，而这正是最难自查的一类故障（设置看起来保存了、
     * 文件里也确实是空的，就是行为不对）。</p>
     */
    private static String readKeyKeepingEmpty(JsonObject json, String key, String fallback) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            return fallback;
        }
        return value.getAsString().trim();
    }
}
