package neko.client.fill.litematica;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 标题形态的纯逻辑判据。
 *
 * <p>标题写错的代价是「看不出这张表在算哪座建筑」，而界面本身一切正常 ——
 * 属于典型的不报错型缺陷，所以三种形态逐条钉住。</p>
 */
class ContainerTitleTest {

    @Test
    void noPlacementSaysSo() {
        ContainerTitle.Title title = ContainerTitle.of(List.of());
        assertEquals(ContainerTitle.KEY_NONE, title.key());
        assertEquals(0, title.args().length);

        assertEquals(ContainerTitle.KEY_NONE, ContainerTitle.of(null).key());
    }

    @Test
    void onePlacementShowsItsName() {
        ContainerTitle.Title title = ContainerTitle.of(List.of("164K抗卸载7gt刷沙机"));
        assertEquals(ContainerTitle.KEY_ONE, title.key());
        assertEquals(1, title.args().length);
        // 🔴 必须是原理图名字本身，不能是「容器填充物合计」这种写死的通用标题。
        assertEquals("164K抗卸载7gt刷沙机", title.args()[0]);
    }

    @Test
    void manyPlacementsShowFirstAndTheRest() {
        ContainerTitle.Title title = ContainerTitle.of(List.of("刷沙机", "刷石机", "南瓜机"));
        assertEquals(ContainerTitle.KEY_MANY, title.key());
        assertEquals("刷沙机", title.args()[0]);
        // 参数是「还有几个」，不是总数：三个投影 = 第一个 + 另外 2 个。
        assertEquals(2, title.args()[1]);
    }

    @Test
    void blankNameStillRendersSomething() {
        ContainerTitle.Title title = ContainerTitle.of(List.of(""));
        assertEquals(ContainerTitle.KEY_ONE, title.key());
        assertEquals("?", title.args()[0], "名字为空时不能留一对空引号");
    }
}
