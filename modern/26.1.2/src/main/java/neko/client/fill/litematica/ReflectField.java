package neko.client.fill.litematica;

import java.lang.reflect.Field;

/**
 * 按<b>类型</b>从对象里读一个字段（不依赖字段名）。
 *
 * <h2>为什么要它</h2>
 * <p>Litematica 的 {@code MaterialListPlacement} / {@code MaterialListSchematic} 把
 * 「我对应哪个投影 / 哪张原理图」放在 private final 字段里，<b>没有 getter</b>
 * （五版本 javap 逐个核对过）。而它们又都是 Litematica 的内部实现，改个名我们不该跟着挂 ——
 * 所以按类型找，不按名字找：类型是 Litematica 的 public 类，稳定得多。</p>
 *
 * <h2>为什么单独一个类</h2>
 * <p>调用方 {@link ContainerScope} 直接引用 Litematica，测试运行时类路径里没有它；
 * 本类<b>零依赖</b>，所以「直接字段 / 父类字段 / 没有该类型 / null 宿主」这四种情形
 * 可以真的在 JUnit 里跑一遍。这条路径一旦坏掉，按钮就会退回「扫全部投影」
 * —— 正是要修的那个 bug，必须有咬得住的判据。</p>
 *
 * <h2>失败怎么办</h2>
 * <p>读不到一律返回 {@code null}，不抛。调用方都备了兜底路径；为了一个「范围算错」的
 * 小问题把「按按钮」升级成「崩游戏」不划算。</p>
 */
public final class ReflectField {

    private ReflectField() {
    }

    /**
     * 在 {@code owner} 的类（及其父类）里，找<b>第一个</b>类型与 {@code type} 兼容的字段并取值。
     *
     * @return 字段值；{@code owner} 为 {@code null}、类型不匹配、字段不可读时返回 {@code null}
     */
    public static <T> T ofType(Object owner, Class<T> type) {
        if (owner == null || type == null) {
            return null;
        }
        for (Class<?> clazz = owner.getClass(); clazz != null; clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!type.isAssignableFrom(field.getType())) {
                    continue;
                }
                try {
                    field.setAccessible(true);
                    return type.cast(field.get(owner));
                } catch (ReflectiveOperationException | RuntimeException e) {
                    return null;
                }
            }
        }
        return null;
    }
}
