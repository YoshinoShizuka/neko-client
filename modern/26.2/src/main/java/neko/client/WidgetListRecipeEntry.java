package neko.client;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import neko.voidwarehouse.recipe.RecipePagePayload;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方本列表里的一行：{@code [图标]} {@code 铁剑 ×1} + 原料摘要 + 一个「删除」按钮。
 *
 * <h2>0.5.0：为什么加图标</h2>
 * <p>0.4.x 只画文字，玩家要在「铁剑 ×1 / 原料：铁锭 ×2、木棍 ×1」这种两行密排里认东西，
 * 一眼扫十行基本不可能 —— 物品图标是人脑里最快的识别路径（原版合成书、JEI 全靠它）。
 * 图标一加，行的辨识成本从「读四个字」变成「看一眼」。</p>
 *
 * <h2>为什么这里可以用 {@code ctx.renderItem} 而不分版本</h2>
 * <p>两代客户端的渲染入口本来是两套（26.x 是 {@code GuiGraphicsExtractor#item(...)}，
 * 1.21.11 是 {@code GuiGraphics#renderItem(...)}）。但 malilib 的 {@link GuiContext}：</p>
 * <ul>
 *   <li>26.x：<b>自己声明了</b> {@code renderItem(ItemStack,int,int)} / {@code renderItemDecorations(...)}
 *       两个包装（0.28.12 / 0.29.6 / 0.30.1 都核过）；</li>
 *   <li>1.21.11：{@code GuiContext extends GuiGraphics}，这两个方法<b>从父类继承</b>下来。</li>
 * </ul>
 * <p>所以同一行 {@code ctx.renderItem(stack, x, y)} 在五个版本上都能编译、都能画 ——
 * 不需要为了一行 16×16 的图标把界面拆成两套代码。</p>
 *
 * <h2>删除按钮为什么要变文字</h2>
 * <p>服务端是<b>全局真删</b>（一份本子全服共用）且没有撤销。点一次就毁掉所有人那一格，
 * 代价太高，所以第一次点击只把按钮变成「确认删除？」，3 秒内再点才真发。
 * 状态存在 {@link RecipeClientState} 里而不是按钮里 —— 列表会随每次回包刷新，
 * 按钮活不过一秒，状态存在控件里必然丢。</p>
 */
public class WidgetListRecipeEntry extends WidgetListEntryBase<RecipePagePayload.Entry> {

    /** 图标 16×16，左上角离行首 4px */
    private static final int ICON_SIZE = 16;
    private static final int ICON_X = 4;
    private static final int ICON_Y = 3;
    /** 图标右侧留 4px 再写字 */
    private static final int TEXT_OFFSET = ICON_X + ICON_SIZE + 4;

    private final int entryX;
    private final int entryY;

    public WidgetListRecipeEntry(int x, int y, int width, int height, boolean isOdd,
                                 RecipePagePayload.Entry entry, int listIndex) {
        super(x, y, width, height, entry, listIndex);
        this.entryX = x;
        this.entryY = y;

        String id = entry.recipeId();
        boolean armed = RecipeClientState.isArmed(id);
        String label = armed ? "确认删除？" : "删除";
        ButtonGeneric button = new ButtonGeneric(x + width - 62, y + 4, 58, 16, label,
                armed ? "再点一次就真的删掉（全服共用，没有撤销）" : "点两次确认删除");
        this.addButton(button, (base, mouseButton) -> RecipeClientState.tapDelete(id));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean isSelected) {
        RecipePagePayload.Entry entry = this.getEntry();
        boolean armed = RecipeClientState.isArmed(entry.recipeId());

        this.renderIcon(ctx, entry);

        String name = SearchCatalogResolver.nameOf(entry.output());
        String label = RecipeBrowseModel.rowLabel(name, entry.outputCount());
        List<String> parts = new ArrayList<>();
        if (entry.parts() != null) {
            for (RecipePagePayload.Part part : entry.parts()) {
                if (part == null || part.candidates() == null || part.candidates().isEmpty()) {
                    continue;
                }
                String partName = SearchCatalogResolver.nameOf(part.candidates().get(0));
                parts.add(partName + " ×" + Math.max(1, part.count()));
            }
        }

        int color = armed ? 0xFFFF5555 : 0xFFFFFFFF;
        this.drawString(ctx, this.entryX + TEXT_OFFSET, this.entryY + 3, color, label);
        this.drawString(ctx, this.entryX + TEXT_OFFSET, this.entryY + 14, 0xFFAAAAAA,
                RecipeBrowseModel.partsLine(parts));

        // 子控件（删除按钮）永远最后画，否则会被文字盖住
        super.render(ctx, mouseX, mouseY, isSelected);
    }

    /**
     * 画产物图标，并把「一次产几个」标在角上。
     *
     * <p>数量走 {@code renderItemDecorations}：和背包里看到的数字一模一样，
     * 玩家不需要另学一套「×N 是产出数」的说法（数量为 1 时它什么都不画，正好）。</p>
     */
    private void renderIcon(GuiContext ctx, RecipePagePayload.Entry entry) {
        ItemStack icon = SearchCatalogResolver.stackOf(entry.output());
        if (icon.isEmpty()) {
            // 客户端不认识这个物品（服务端装了客户端没有的模组）：画一个空框占位，
            // 别让「这一行没有图标」变成一个需要解释的谜
            return;
        }
        icon.setCount(Math.max(1, Math.min(icon.getMaxStackSize(), entry.outputCount())));
        int x = this.entryX + ICON_X;
        int y = this.entryY + ICON_Y;
        ctx.renderItem(icon, x, y);
        ctx.renderItemDecorations(ctx.fontRenderer(), icon, x, y);
    }
}
