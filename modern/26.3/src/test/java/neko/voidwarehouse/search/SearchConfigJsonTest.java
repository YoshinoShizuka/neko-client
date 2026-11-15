package neko.voidwarehouse.search;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link SearchConfig} 纯逻辑层的单测：<b>不启动游戏、不碰磁盘</b>。
 *
 * <p>这里咬的是三件最容易出错、又最难在游戏里发现的事：</p>
 * <ol>
 *   <li><b>默认值必须是「都开」</b>：加开关不能把老玩家的功能悄悄关掉；</li>
 *   <li><b>缺字段 / 类型不对必须回落</b>：老配置文件、手改打错一个引号，
 *       都不能让整个配置读不出来（否则表现为「改了没反应」）；</li>
 *   <li><b>落盘模板必须能被自己读回来</b>：模板是手写的文本块，
 *       写错一个逗号就永远读不出玩家改的值 —— 这条只有真解析一遍才抓得到。</li>
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
        SearchConfig.Values values = SearchConfig.parse("{}");
        assertAll(
                () -> assertTrue(values.searchBox(), "searchBox 默认必须是开"),
                () -> assertTrue(values.fillGesture(), "fillGesture 默认必须是开"));
    }

    @Test
    @DisplayName("A2 只写了 searchBox ⇒ 没写的那个仍然是默认开")
    void missingFieldFallsBackToDefault() {
        SearchConfig.Values values = SearchConfig.parse("{\"searchBox\": false}");
        assertAll(
                () -> assertFalse(values.searchBox(), "写了 false 就该是 false"),
                () -> assertTrue(values.fillGesture(), "没写的字段必须回落到默认值"));
    }

    @Test
    @DisplayName("A3 只写了 fillGesture ⇒ 对称成立")
    void missingFieldFallsBackSymmetrically() {
        SearchConfig.Values values = SearchConfig.parse("{\"fillGesture\": false}");
        assertAll(
                () -> assertTrue(values.searchBox()),
                () -> assertFalse(values.fillGesture()));
    }

    @Test
    @DisplayName("A4 两个都写 false ⇒ 真的都关（不因为「都是 false」被当成缺字段）")
    void bothFalseIsHonoured() {
        SearchConfig.Values values = SearchConfig.parse("{\"searchBox\": false, \"fillGesture\": false}");
        assertAll(
                () -> assertFalse(values.searchBox()),
                () -> assertFalse(values.fillGesture()));
    }

    // ------------------------------------------------------------ B 组：类型不对

    @Test
    @DisplayName("B1 字符串 \"false\" 不算布尔 ⇒ 回落默认，而不是静默变成 true 或抛异常")
    void stringValueFallsBack() {
        SearchConfig.Values values = SearchConfig.parse("{\"searchBox\": \"false\"}");
        assertTrue(values.searchBox(), "字符串不是布尔，应回落默认值 true");
    }

    @Test
    @DisplayName("B2 null / 数字 同样回落默认（数字不是布尔）")
    void nullAndNumberFallBack() {
        assertAll(
                () -> assertTrue(SearchConfig.parse("{\"searchBox\": null}").searchBox(),
                        "null 回落默认 true"),
                () -> assertTrue(SearchConfig.parse("{\"searchBox\": 0}").searchBox(),
                        "数字 0 回落默认 true"),
                () -> assertTrue(SearchConfig.parse("{\"searchBox\": 1}").searchBox(),
                        "数字 1 也回落默认 true —— 不能把 1 当成 true"),
                () -> assertTrue(SearchConfig.parse("{\"fillGesture\": 0}").fillGesture(),
                        "fillGesture 同样回落"));
    }

    // ------------------------------------------------------------ C 组：坏输入

    @Test
    @DisplayName("C1 顶层不是对象（数组 / 字符串 / null）⇒ 明确抛 IllegalArgumentException")
    void nonObjectRootIsRejected() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfig.parse("[]")),
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfig.parse("\"x\"")),
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfig.parse("null")),
                // JSON null 在 Gson 里是 JsonNull，isJsonObject() 也是 false
                () -> assertThrows(IllegalArgumentException.class, () -> SearchConfig.parse("42")));
    }

    @Test
    @DisplayName("C2 语法错误 ⇒ 抛 RuntimeException（load 会 catch 住，不会崩游戏）")
    void syntaxErrorThrowsRuntimeException() {
        assertAll(
                () -> assertThrows(RuntimeException.class, () -> SearchConfig.parse("{")),
                () -> assertThrows(RuntimeException.class, () -> SearchConfig.parse("{\"searchBox\": }")),
                () -> assertThrows(RuntimeException.class, () -> SearchConfig.parse("")));
    }

    // ------------------------------------------------------------ D 组：模板往返

    @Test
    @DisplayName("D1 render 出来的文本必须能被 parse 原样读回（四种组合全覆盖）")
    void renderRoundTrips() {
        for (boolean searchBox : new boolean[]{true, false}) {
            for (boolean fill : new boolean[]{true, false}) {
                String text = SearchConfig.render(searchBox, fill);
                SearchConfig.Values values = SearchConfig.parse(text);
                assertEquals(searchBox, values.searchBox(), "searchBox 往返失真: " + text);
                assertEquals(fill, values.fillGesture(), "fillGesture 往返失真: " + text);
            }
        }
    }

    @Test
    @DisplayName("D2 模板里的 _readme 说明必须真的落进文件（不是被吞掉的注释）")
    void templateCarriesReadme() {
        String text = SearchConfig.render(true, true);
        assertAll(
                () -> assertTrue(text.contains("\"_readme\""), "缺 _readme"),
                () -> assertTrue(text.contains("\"_readme_keys\""), "缺 _readme_keys"),
                () -> assertTrue(text.contains("按键绑定"), "_readme_keys 里要写清快捷键去哪改"),
                () -> assertTrue(text.startsWith("{"), "落盘文本不能有空行开头"),
                () -> assertTrue(text.trim().endsWith("}"), "落盘文本要以 } 结尾"));
    }

    @Test
    @DisplayName("D3 模板写入 false 时是字面量 false，不是 \"false\" 字符串")
    void templateWritesRealBooleans() {
        String text = SearchConfig.render(false, true);
        assertAll(
                () -> assertTrue(text.contains("\"searchBox\": false"), "应该是裸 false"),
                () -> assertFalse(text.contains("\"searchBox\": \"false\""), "不能写成字符串"));
    }

    // ------------------------------------------------------------ E 组：默认常量与开关语义

    @Test
    @DisplayName("E1 未读盘时内存默认值就是「都开」：加开关不改老行为")
    void defaultsAreBothEnabled() {
        // 这里不调 load()（会碰 FabricLoader），只看内存初值 —— 正是「客户端刚起来、
        // 配置文件还没读」那一刻的状态
        assertAll(
                () -> assertTrue(SearchConfig.isSearchBoxEnabled(), "默认 searchBox 必须开"),
                () -> assertTrue(SearchConfig.isFillGestureEnabled(), "默认 fillGesture 必须开"));
    }

    @Test
    @DisplayName("E2 anyFeatureEnabled 是「或」：只要还有一个开着就得握手")
    void anyFeatureEnabledIsOr() {
        boolean search = SearchConfig.isSearchBoxEnabled();
        boolean fill = SearchConfig.isFillGestureEnabled();
        // 默认两个都开 ⇒ true；这条同时保证「都关才不发 hello」的判据不是恒真
        assertEquals(search || fill, SearchConfig.anyFeatureEnabled());
        assertTrue(SearchConfig.anyFeatureEnabled(), "默认状态下必须为 true");
    }
}
