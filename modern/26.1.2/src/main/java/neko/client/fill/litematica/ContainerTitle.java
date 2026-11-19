package neko.client.fill.litematica;

import java.util.List;

/**
 * 「容器填充物合计」界面的标题（纯逻辑，可单测）。
 *
 * <h2>为什么要带原理图名字</h2>
 * <p>没有名字的标题等于没有标题：玩家同时放着好几个投影时，一张写着「容器填充物合计」的
 * 表根本看不出是在算哪一座建筑。Litematica 自己的材料列表就是
 * 「原理图'&lt;名字&gt;'材料列表」的形态，这里照抄它的读法，只把「材料列表」换成
 * 「容器材料」。</p>
 *
 * <h2>为什么三种形态</h2>
 * <p>合计是「所有<b>启用</b>的投影」一起算的（见 {@code QuickLitematicaContainerTotals}），
 * 所以一个名字不够用：</p>
 * <ul>
 *   <li>0 个：标题得自己说明「没有启用的投影」，否则空表看着像坏了；</li>
 *   <li>1 个：最常见，直接写名字；</li>
 *   <li>多个：写第一个 + 「等 N 个」—— 全列出来会把标题撑出屏幕。</li>
 * </ul>
 *
 * <p>本类只管「用哪个键、带什么参数」，翻译交给 malilib
 * （{@code StringUtils.translate}）—— 与其余界面文字同一个来源。</p>
 */
public final class ContainerTitle {

    /** 一种形态：语言键 + 它的参数。 */
    public record Title(String key, Object[] args) {
    }

    public static final String KEY_NONE = "neko_client.materials.title.none";
    public static final String KEY_ONE = "neko_client.materials.title.one";
    public static final String KEY_MANY = "neko_client.materials.title.many";

    private ContainerTitle() {
    }

    /** 按「启用投影的名字列表」给出标题形态。 */
    public static Title of(List<String> names) {
        if (names == null || names.isEmpty()) {
            return new Title(KEY_NONE, new Object[0]);
        }
        String first = names.get(0);
        if (first == null || first.isEmpty()) {
            first = "?";
        }
        if (names.size() == 1) {
            return new Title(KEY_ONE, new Object[] {first});
        }
        return new Title(KEY_MANY, new Object[] {first, names.size() - 1});
    }
}
