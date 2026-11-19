package neko.client.fill.litematica;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 按类型读字段的纯逻辑判据。
 *
 * <p>这条路一旦读不到，{@code ContainerScope} 就会退回「扫所有启用的投影」——
 * 也就是 0.5.7 真机反馈的那个 bug（从任何一张图的材料列表点按钮，都开出同一份表）。
 * 而它本身不报错、不提示，界面一切正常，只有内容是错的 ⇒ 必须有单测钉住。</p>
 *
 * <p>测的是 {@link ReflectField}，不是 {@code ContainerScope}：后者引用 Litematica，
 * 测试运行时类路径里没有它；用本文件里的假类就能真跑。</p>
 */
class ReflectFieldTest {

    /** 假装这是 {@code SchematicPlacement}。 */
    static final class Marker {
    }

    static final class NotMarker {
    }

    /** 直接声明字段（对应 {@code MaterialListPlacement.placement}）。 */
    static final class Direct {
        private final Marker marker = new Marker();
    }

    /** 字段在父类上（Litematica 若改成继承，这里仍要能找到）。 */
    static class Base {
        private final Marker inherited = new Marker();
    }

    static final class Child extends Base {
        private final NotMarker other = new NotMarker();
    }

    /** 只有别的类型（对应区域选择的材料列表：压根没有投影）。 */
    static final class Unrelated {
        private final NotMarker other = new NotMarker();
        private int count = 3;
    }

    @Test
    void readsPrivateFieldOfThatType() {
        assertNotNull(ReflectField.ofType(new Direct(), Marker.class),
                "private final 字段也要读得到（Litematica 两个材料列表都是这样存的）");
    }

    @Test
    void walksUpToSuperclass() {
        assertNotNull(ReflectField.ofType(new Child(), Marker.class),
                "字段在父类上时必须继续往上找，不能只看当前类");
    }

    @Test
    void ignoresFieldsOfOtherTypes() {
        assertNull(ReflectField.ofType(new Unrelated(), Marker.class),
                "同名的没有、别的类型的字段不能误命中");
    }

    @Test
    void nullHostOrTypeIsNull() {
        assertNull(ReflectField.ofType(null, Marker.class));
        assertNull(ReflectField.ofType(new Direct(), null));
    }
}
