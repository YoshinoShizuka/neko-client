package neko.client.mixin;

import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * 暴露 malilib 的按钮列表（{@link GuiBase} 的 {@code private final List<ButtonBase> buttons}）。
 *
 * <h2>为什么非要这个访问器</h2>
 * <p>要在材料列表工具栏「最后一个按钮右侧」（截图红框）加按钮，就得知道那个按钮的
 * 右边缘。三条看似能走的路都堵死了：</p>
 * <ul>
 *   <li>{@code createButtons()} 末尾拿不到局部变量 {@code x}（TAIL 时局部变量已交出）；</li>
 *   <li>{@code createButton(int, int, ButtonListener.Type)} 的第三个参数是
 *       {@code GuiMaterialList$ButtonListener$Type} —— {@code ButtonListener} 是
 *       <b>private record</b>，Java 源码里根本写不出这个类型，{@code @Inject} 的处理方法
 *       签名对不上（Mixin 要求描述符逐字相等）；</li>
 *   <li>自己按 {@code x += 宽度} 重算：宽度来自 {@code exportType.getDisplayName()}，
 *       而 {@code exportType} 也是 private 字段。</li>
 * </ul>
 * <p>所以反过来 —— 按钮都建完以后直接读按钮列表，取该行最右的
 * {@code getX() + getWidth()}。{@code buttons} 在 malilib 0.28.12 / 0.29.6 / 0.30.1 /
 * 0.27.20（1.21.11）里都是同一个 private final 字段（javap 逐版本核对）。</p>
 *
 * <p>🔴 这个访问器的目标类是 <b>malilib</b>（硬依赖，见 fabric.mod.json 的 depends），
 * 所以放在必加载的 {@code neko_client.mixins.json} 里：malilib 万一改了字段名要<b>当场报错</b>，
 * 不能悄悄不生效。litematica 是软依赖，那边的混入才放
 * {@code neko_client_litematica.mixins.json}（{@code required: false}）。</p>
 */
@Mixin(GuiBase.class)
public interface AccessorGuiBase {

    @Accessor("buttons")
    List<ButtonBase> voidwarehouseSearch$getButtons();
}
