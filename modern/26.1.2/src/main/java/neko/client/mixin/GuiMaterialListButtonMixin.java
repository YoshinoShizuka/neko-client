package neko.client.mixin;

import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.malilib.gui.button.ButtonBase;
import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.util.StringUtils;
import neko.client.fill.litematica.ContainerFillMaterialList;
import neko.client.fill.litematica.ToolbarButtonLayout;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * 在 Litematica 材料列表（{@link GuiMaterialList}）的工具栏最右一个按钮之后，
 * 加一个「容器材料」按钮 —— 点它打开 {@link ContainerFillMaterialList}
 * （整张原理图容器填充物合计 vs 背包 + 虚空仓库库存）。
 *
 * <p>打开的是<b>本界面这份材料列表对应那张原理图</b>：按钮回调把
 * {@code self.getMaterialList()} 一起传下去，由 {@code ContainerScope} 反推投影。
 * 不传的话所有入口都会开出同一份表。</p>
 *
 * <h2>为什么是 TAIL of createButtons()</h2>
 * <p>{@code createButtons()} 是唯一建工具栏的地方，建完时才拿得到「该行最右边缘」。
 * {@code createButton} 的第三个参数是 private record 里的枚举，源码写不出来（见
 * {@link AccessorGuiBase} 的注释），所以位置不自己算，直接读按钮列表。</p>
 *
 * <h2>会不会加两次</h2>
 * <p>不会。能重进 {@code createButtons()} 的地方只有两处，都先清了按钮：
 * ① {@code GuiBase.initGui()} → {@code clearElements()} → {@code clearButtons()}
 * （改窗口大小会再走一遍）；② {@code ExportTypeWrapper.setOptionListValue()} 里
 * {@code clearButtons()} 之后紧接着调。所以每次都是「清空 → 重建 → 我们补一个」。</p>
 *
 * <h2>litematica 没装会怎样</h2>
 * <p>本混入在 {@code neko_client_litematica.mixins.json}（{@code required: false}）里，
 * 目标类不存在时整份配置静默跳过，其余功能照旧。</p>
 */
@Mixin(GuiMaterialList.class)
public class GuiMaterialListButtonMixin {

    private static final String LABEL_KEY = "neko_client.materials.button";
    private static final String HOVER_KEY = "neko_client.materials.button.hover";

    @Inject(method = "createButtons", at = @At("TAIL"))
    private void neko$addContainerTotalsButton(CallbackInfo ci) {
        GuiMaterialList self = (GuiMaterialList) (Object) this;
        List<ButtonBase> buttons = ((AccessorGuiBase) (Object) this).voidwarehouseSearch$getButtons();
        List<ToolbarButtonLayout.Box> boxes = new ArrayList<>(buttons.size());
        for (ButtonBase button : buttons) {
            boxes.add(new ToolbarButtonLayout.Box(button.getX(), button.getY(), button.getWidth()));
        }

        int rowY = ToolbarButtonLayout.rowY(boxes);
        int right = ToolbarButtonLayout.rightmost(boxes, rowY);
        String label = StringUtils.translate(LABEL_KEY);
        int width = self.getStringWidth(label) + 10;
        int x = ToolbarButtonLayout.xAfter(right, width, self.getScreenWidth());

        ButtonGeneric button = new ButtonGeneric(x, rowY, width, 20, label,
                StringUtils.translate(HOVER_KEY));
        // 🔴 必须把「本界面这份材料列表」传进去：不传就只能扫「所有启用的投影」，
        // 于是从任何一张图点进来都开出同一份表（0.5.7 真机反馈的原症状）。
        self.addButton(button, (clicked, mouseButton) ->
                ContainerFillMaterialList.openCurrent(self.getMaterialList()));
    }
}
