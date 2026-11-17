package neko.client;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchConfigJson} 纯逻辑层的单测：<b>不启动游戏、不碰磁盘、不碰 malilib</b>。
 *
 * <p>为什么测的是 {@code SearchConfigJson} 而不是 {@code SearchConfig}：
 * 后者从 0.3.0 起持有 malilib 的 {@code ConfigBooleanHotkeyed}，那些类在
 * 「没启动游戏」的 JVM 里根本加载不了（静态初始化就摸 MC 的 {@code Identifier}）。
 * 于是文件格式这一层被单独拆出来 —— 它能测，而它恰好是<b>最需要测</b>的那层。</p>
 *
 * <p>这里咬的是四件最容易出错、又最难在游戏里发现的事：</p>
 * <ol>
 *   <li><b>默认值必须是「都开」</b>：加开关不能把老玩家的功能悄悄关掉；</li>
 *   <li><b>缺字段 / 类型不对必须回落</b>：老配置文件、手改打错一个引号，
 *       都不能让整个配置读不出来（否则表现为「改了没反应」）；</li>
 *   <li><b>落盘模板必须能被自己读回来</b>：模板是手写的文本块，
 *       写错一个逗号就永远读不出玩家改的值 —— 这条只有真解析一遍才抓得到；</li>
 *   <li><b>热键串必须是带引号的字符串</b>：{@code "H"} 写丢了引号，整份文件就语法错误。</li>
 * </ol>
 *
 * <p>跑法：{@code TARGET_MC_VERSIONS=26.3 ./gradlew :modern:26.3:test --no-daemon}
 * （只有 26.3 子工程挂了 JUnit，五份源码逐字节相同，测一遍即可）</p>
 */
class SearchConfigJsonTest {

    // ------------------------------------------------------------ A 组：默认与缺字段

    @Test
    @DisplayName("A1 空对象 ⇒ 两个功能都开（装了就用，不改变老行为）")
    void emptyObjectMeansBothOn() {
        SearchConfigJson.Values values = SearchConfigJson.parse("{}");
        assertAll(
                () -> assertTrue(values.searchBox(), "searchBox 默认必须是开"),
                () -> assertTrue(values.fillGesture(), "fillGesture 默认必须是开"));
    }

    @Test
    @DisplayName("A2 只写了 searchBox ⇒ 没写的那个仍然是默认开")
    void missingFieldFallsBackToDefault() {
        SearchConfigJson.Values values = SearchConfigJson.parse("{\"searchBox\": false}");
        assertAll(
                () -> assertFalse(values.searchBox(), "写了 false 就该是 false"),
                () -> assertTrue(values.fillGesture(), "没写的字段必须回落到默认值"));
    }

    @Test
    @DisplayName("A3 只写了 fillGesture ⇒ 对称成立")
    void missingFieldFallsBackSymmetrically() {
        SearchConfigJson.Values values = SearchConfigJson.parse("{\"fillGesture\": false}");
        assertAll(
                () -> assertTrue(values.searchBox()),
                () -> assertFalse(values.fillGesture()));
    }

    @Test
    @DisplayName("A4 两个都写 false ⇒ 真的都关（不因为「都是 false」被当成缺字段）")
    void bothFalseIsHonoured() {
        SearchConfigJson.Values values =
                SearchConfigJson.parse("{\"searchBox\": false, \"fillGesture\": false}");
        assertAll(
                () -> assertFalse(values.searchBox()),
                () -> assertFalse(values.fillGesture()));
    }

    // ------------------------------------------------------------ B 组：类型不对

    @Test
    @DisplayName("B1 字符串 \"false\" 不算布尔 ⇒ 回落默认，而不是静默变成 true 或抛异常")
    void stringValueFallsBack() {
        SearchConfigJson.Values values = SearchConfigJson.parse("{\"searchBox\": \"false\"}");
        assertTrue(values.searchBox(), "字符串不是布尔，应回落默认值 true");
    }

    @Test
    @DisplayName("B2 null / 数字 同样回落默认（数字不是布尔）")
    void nullAndNumberFallBack() {
        assertAll(
                () -> assertTrue(SearchConfigJson.parse("{\"searchBox\": null}").searchBox(),
                        "null 回落默认 true"),
                () -> assertTrue(SearchConfigJson.parse("{\"searchBox\": 0}").searchBox(),
                        "数字 0 回落默认 true"),
                () -> assertTrue(SearchConfigJson.parse("{\"searchBox\": 1}").searchBox(),
                        "数字 1 也回落默认 true —— 不能把 1 当成 true"),
                () -> assertTrue(SearchConfigJson.parse("{\"fillGesture\": 0}").fillGesture(),
                        "fillGesture 同样回落"));
    }

    // ------------------------------------------------------------ C 组：坏输入

    @Test
    @DisplayName("C1 顶层不是对象（数组 / 字符串 / null）⇒ 明确抛 IllegalArgumentException")
    void nonObjectRootIsRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfigJson.parse("[]")),
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfigJson.parse("\"x\"")),
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfigJson.parse("null")),
                // JSON null 在 Gson 里是 JsonNull，isJsonObject() 也是 false
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfigJson.parse("42")));
    }

    @Test
    @DisplayName("C2 语法错误 ⇒ 抛 RuntimeException（load 会 catch 住，不会崩游戏）")
    void syntaxErrorThrowsRuntimeException() {
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> SearchConfigJson.parse("{")),
                () -> assertThrows(RuntimeException.class,
                        () -> SearchConfigJson.parse("{\"searchBox\": }")),
                () -> assertThrows(RuntimeException.class, () -> SearchConfigJson.parse("")));
    }

    // ------------------------------------------------------------ D 组：模板往返

    @Test
    @DisplayName("D1 render 出来的文本必须能被 parse 原样读回（四种开关组合全覆盖）")
    void renderRoundTrips() {
        for (boolean searchBox : new boolean[]{true, false}) {
            for (boolean fill : new boolean[]{true, false}) {
                SearchConfigJson.Values written = new SearchConfigJson.Values(
                        searchBox, fill,
                        SearchConfigJson.DEFAULT_KEY_SEARCH_BOX,
                        SearchConfigJson.DEFAULT_KEY_FILL_GESTURE,
                        SearchConfigJson.DEFAULT_KEY_FILL_COMBO,
                        SearchConfigJson.DEFAULT_KEY_OPEN_GUI,
                        SearchConfigJson.DEFAULT_KEY_OPEN_RECIPES);
                String text = SearchConfigJson.render(written);
                SearchConfigJson.Values values = SearchConfigJson.parse(text);
                assertEquals(written, values, "整份配置往返失真: " + text);
            }
        }
    }

    @Test
    @DisplayName("D2 模板里的 _readme 说明必须真的落进文件（不是被吞掉的注释）")
    void templateCarriesReadme() {
        String text = SearchConfigJson.render(SearchConfigJson.Values.defaults());
        assertAll(
                () -> assertTrue(text.contains("\"_readme\""), "缺 _readme"),
                () -> assertTrue(text.contains("\"_readme_keys\""), "缺 _readme_keys"),
                () -> assertTrue(text.contains("\"_readme_fillCombo\""), "缺 _readme_fillCombo"),
                () -> assertTrue(text.contains("热键"), "_readme_keys 里要写清热键去哪改"),
                // 0.4.6：玩家手改 json 时必须能看到「想用右键该写哪个键」，否则又会
                // 出现「绑左键、实际右键生效」这类说不清的误解
                () -> assertTrue(text.contains("BUTTON_2") && text.contains("不会触发"),
                        "_readme_fillCombo 里要写清右键该绑 BUTTON_2、且左键绑定不会让右键触发"),
                () -> assertTrue(text.contains("配置文件") || text.contains("重进一次世界"),
                        "_readme 里要写清改完什么时候生效"),
                () -> assertTrue(text.startsWith("{"), "落盘文本不能有空行开头"),
                () -> assertTrue(text.trim().endsWith("}"), "落盘文本要以 } 结尾"));
    }

    @Test
    @DisplayName("D3 模板写入 false 时是字面量 false，不是 \"false\" 字符串")
    void templateWritesRealBooleans() {
        String text = renderWith(false, true, "H", "J", "V", "BUTTON_1");
        assertAll(
                () -> assertTrue(text.contains("\"searchBox\": false"), "应该是裸 false"),
                () -> assertFalse(text.contains("\"searchBox\": \"false\""), "不能写成字符串"));
    }

    // ------------------------------------------------------------ E 组：语义判据

    @Test
    @DisplayName("E1 默认值：两个功能都开、三条开关热键未绑定、取满组合键默认=Ctrl+Alt+右键")
    void defaultsAreBothEnabledNoHotkeys() {
        SearchConfigJson.Values values = SearchConfigJson.Values.defaults();
        assertAll(
                () -> assertTrue(values.searchBox(), "默认 searchBox 必须开"),
                () -> assertTrue(values.fillGesture(), "默认 fillGesture 必须开"),
                () -> assertEquals("", values.keySearchBox(), "搜索框开关默认应未绑定"),
                () -> assertEquals("", values.keyFillGesture(), "取满开关默认应未绑定"),
                () -> assertEquals("", values.keyOpenGui(), "开界面默认应未绑定"),
                // 🔴 唯一的例外：取满组合键必须有默认值，否则玩家看到「功能开着却没反应」。
                // 0.4.7 起默认改成右键（真机确认左键绑定不生效、右键有效），
                // 默认值只影响新生成的配置，已有 keyFillCombo 不会被改写。
                () -> assertEquals("LEFT_CONTROL,LEFT_ALT,BUTTON_2", values.keyFillCombo(),
                        "0.4.7 起取满组合键默认 Ctrl+Alt+右键（BUTTON_2）"));
    }

    @Test
    @DisplayName("E2 anyEnabled 是「或」：只要还有一个开着就得握手，都关才不握手")
    void anyEnabledIsOr() {
        assertAll(
                () -> assertTrue(SearchConfigJson.Values.defaults().anyEnabled(), "默认必须握手"),
                () -> assertTrue(values(true, false).anyEnabled(), "只开搜索框也要握手"),
                () -> assertTrue(values(false, true).anyEnabled(), "只开取满也要握手"),
                () -> assertFalse(values(false, false).anyEnabled(), "都关才不握手"));
    }

    // ------------------------------------------------------------ F 组：热键字段

    @Test
    @DisplayName("F1 缺热键字段 ⇒ 三条开关热键回落「未绑定」；取满组合键回落老行为的默认组合")
    void missingHotkeysFallBackToUnbound() {
        SearchConfigJson.Values values = SearchConfigJson.parse("{}");
        assertAll(
                () -> assertEquals("", values.keySearchBox()),
                () -> assertEquals("", values.keyFillGesture()),
                () -> assertEquals("", values.keyOpenGui()),
                () -> assertEquals(SearchConfigJson.DEFAULT_KEY_FILL_COMBO, values.keyFillCombo(),
                        "组合键的默认值非空，缺字段时要回落到它，不是回落到空"));
    }

    @Test
    @DisplayName("F2 空白 / 非字符串热键 ⇒ 视为未绑定；首尾空格要吃掉")
    void blankOrWrongTypedHotkeysFallBack() {
        assertAll(
                () -> assertEquals("", SearchConfigJson.parse("{\"keySearchBox\": \"   \"}").keySearchBox(),
                        "纯空白视为未绑定"),
                () -> assertEquals("", SearchConfigJson.parse("{\"keySearchBox\": \"\"}").keySearchBox(),
                        "空串视为未绑定"),
                () -> assertEquals("", SearchConfigJson.parse("{\"keySearchBox\": 72}").keySearchBox(),
                        "数字不是热键串，不能原样当成 \"72\""),
                () -> assertEquals("", SearchConfigJson.parse("{\"keyOpenGui\": [\"V\"]}").keyOpenGui(),
                        "数组不是热键串"),
                () -> assertEquals("H", SearchConfigJson.parse("{\"keySearchBox\": \"  H  \"}").keySearchBox(),
                        "正常值不能被空白判据误吞，但要 trim"));
    }

    @Test
    @DisplayName("F3 组合键串往返不失真（malilib 的逗号分隔写法）")
    void comboHotkeyRoundTrips() {
        SearchConfigJson.Values written = new SearchConfigJson.Values(
                true, false, "LEFT_CONTROL,LEFT_ALT", "J", "BUTTON_2,LEFT_SHIFT", "RIGHT_SHIFT,V",
                "LEFT_ALT,G");
        assertEquals(written, SearchConfigJson.parse(SearchConfigJson.render(written)));
    }

    @Test
    @DisplayName("F4 模板里的热键是带引号的字符串；未绑定时落成空串而不是 null")
    void templateQuotesHotkeys() {
        String bound = renderWith(true, true, "H", "J", "V", "BUTTON_1");
        String unbound = SearchConfigJson.render(SearchConfigJson.Values.defaults());
        assertAll(
                () -> assertTrue(bound.contains("\"keySearchBox\": \"H\""), "热键必须加引号"),
                () -> assertTrue(bound.contains("\"keyFillGesture\": \"J\""), "热键必须加引号"),
                () -> assertTrue(bound.contains("\"keyOpenGui\": \"V\""), "热键必须加引号"),
                () -> assertTrue(bound.contains("\"keyFillCombo\": \"BUTTON_1\""), "组合键必须加引号"),
                () -> assertTrue(unbound.contains("\"keySearchBox\": \"\""), "未绑定要写成空串"),
                () -> assertFalse(unbound.contains("null"), "不能落成 null"),
                () -> assertTrue(unbound.contains("\"keyFillCombo\": \"LEFT_CONTROL,LEFT_ALT,BUTTON_2\""),
                        "组合键默认必须落盘，玩家才知道它不是空的、可以改"));
    }

    // ------------------------------------------------------------ G 组：取满组合键的「清空」语义

    @Test
    @DisplayName("G1 🔴 字段缺失 ⇒ 默认组合键；字段在但值是空串 ⇒ 就是空（玩家主动关掉了手势）")
    void fillComboDistinguishesMissingFromCleared() {
        assertAll(
                () -> assertEquals(SearchConfigJson.DEFAULT_KEY_FILL_COMBO,
                        SearchConfigJson.parse("{}").keyFillCombo(),
                        "老配置文件里没有这个字段 ⇒ 必须回落到老行为（Ctrl+Alt+左键）"),
                () -> assertEquals("",
                        SearchConfigJson.parse("{\"keyFillCombo\": \"\"}").keyFillCombo(),
                        "玩家清空了组合键 ⇒ 必须原样保持为空，不能又长回默认值"));
    }

    @Test
    @DisplayName("G2 清空后的空串要能穿过 render→parse 一轮（「关不掉」就是在这里丢的）")
    void clearedFillComboSurvivesRoundTrip() {
        SearchConfigJson.Values cleared = new SearchConfigJson.Values(
                true, true,
                SearchConfigJson.DEFAULT_KEY_SEARCH_BOX,
                SearchConfigJson.DEFAULT_KEY_FILL_GESTURE,
                "",   // ← 玩家在配置界面里把组合键清掉了
                SearchConfigJson.DEFAULT_KEY_OPEN_GUI,
                SearchConfigJson.DEFAULT_KEY_OPEN_RECIPES);
        SearchConfigJson.Values readBack = SearchConfigJson.parse(SearchConfigJson.render(cleared));
        assertEquals("", readBack.keyFillCombo(),
                "清空后重进世界组合键又回来了 —— 表现为「关不掉这个手势」");
    }

    @Test
    @DisplayName("G3 空白串按清空处理（要 trim）；非字符串仍回落默认组合")
    void fillComboBlankIsClearedButWrongTypeFallsBack() {
        assertAll(
                () -> assertEquals("", SearchConfigJson.parse("{\"keyFillCombo\": \"   \"}").keyFillCombo(),
                        "纯空白 = 清空"),
                () -> assertEquals(SearchConfigJson.DEFAULT_KEY_FILL_COMBO,
                        SearchConfigJson.parse("{\"keyFillCombo\": 99}").keyFillCombo(),
                        "数字不是热键串 ⇒ 回落默认组合，不能当成 \"99\""),
                () -> assertEquals(SearchConfigJson.DEFAULT_KEY_FILL_COMBO,
                        SearchConfigJson.parse("{\"keyFillCombo\": null}").keyFillCombo(),
                        "null ⇒ 回落默认组合"),
                () -> assertEquals("BUTTON_1",
                        SearchConfigJson.parse("{\"keyFillCombo\": \"  BUTTON_1 \"}").keyFillCombo(),
                        "正常值不能被空白判据误吞，但要 trim"));
    }

    // ------------------------------------------------------------ 小工具

    private static SearchConfigJson.Values values(boolean searchBox, boolean fillGesture) {
        return new SearchConfigJson.Values(searchBox, fillGesture,
                SearchConfigJson.DEFAULT_KEY_SEARCH_BOX,
                SearchConfigJson.DEFAULT_KEY_FILL_GESTURE,
                SearchConfigJson.DEFAULT_KEY_FILL_COMBO,
                SearchConfigJson.DEFAULT_KEY_OPEN_GUI,
                SearchConfigJson.DEFAULT_KEY_OPEN_RECIPES);
    }

    private static String renderWith(boolean searchBox, boolean fillGesture,
                                     String keySearchBox, String keyFillGesture,
                                     String keyOpenGui, String keyFillCombo) {
        return SearchConfigJson.render(new SearchConfigJson.Values(
                searchBox, fillGesture, keySearchBox, keyFillGesture, keyFillCombo, keyOpenGui,
                SearchConfigJson.DEFAULT_KEY_OPEN_RECIPES));
    }
}
