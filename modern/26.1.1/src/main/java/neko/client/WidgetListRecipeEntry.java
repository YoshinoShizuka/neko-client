package neko.client;

import fi.dy.masa.malilib.gui.button.ButtonGeneric;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntryBase;
import fi.dy.masa.malilib.render.GuiContext;
import neko.voidwarehouse.recipe.RecipePagePayload;

import java.util.ArrayList;
import java.util.List;

/**
 * 配方本列表里的一行：{@code 铁剑 ×1} + 原料摘要 + 一个「删除」按钮。
 *
 * <h2>为什么不画物品图标</h2>
 * <p>画图标要碰原版渲染 API，而两代客户端的渲染入口不同（26.x 是
 * {@code extractRenderState(GuiGraphicsExtractor,…)}、1.21.11 是
 * {@code render(GuiGraphics,…)}）。malilib 自己抽了一层 {@link GuiContext} 把这件事抹平了，
 * 但图标绘制不在那层抽象里 —— 为了一行 16×16 的图标把整个界面拆成两套代码不值得。
 * 产物名字是玩家真正要认的东西，图标只是辅助。</p>
 *
 * <h2>删除按钮为什么要变文字</h2>
 * <p>服务端是<b>全局真删</b>（一份本子全服共用）且没有撤销。点一次就毁掉所有人那一格，
 * 代价太高，所以第一次点击只把按钮变成「确认删除？」，3 秒内再点才真发。
 * 状态存在 {@link RecipeClientState} 里而不是按钮里 —— 列表会随每次回包重建，
 * 按钮活不过一秒，状态存在控件里必然丢。</p>
 */
public class WidgetListRecipeEntry extends WidgetListEntryBase<RecipePagePayload.Entry> {

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
        ButtonGeneric button = new ButtonGeneric(x + width - 62, y + 3, 58, 18, label,
                armed ? "再点一次就真的删掉（全服共用，没有撤销）" : "点两次确认删除");
        this.addButton(button, (base, mouseButton) -> RecipeClientState.tapDelete(id));
    }

    @Override
    public void render(GuiContext ctx, int mouseX, int mouseY, boolean isSelected) {
        RecipePagePayload.Entry entry = this.getEntry();
        boolean armed = RecipeClientState.isArmed(entry.recipeId());

        String name = SearchCatalogResolver.displayNameOf(entry.output());
        String label = RecipeBrowseModel.rowLabel(name, entry.outputCount());
        List<String> parts = new ArrayList<>();
        if (entry.parts() != null) {
            for (RecipePagePayload.Part part : entry.parts()) {
                if (part == null || part.candidates() == null || part.candidates().isEmpty()) {
                    continue;
                }
                String partName = SearchCatalogResolver.displayNameOf(part.candidates().get(0));
                parts.add(partName + " ×" + Math.max(1, part.count()));
            }
        }

        int color = armed ? 0xFFFF5555 : 0xFFFFFFFF;
        this.drawString(ctx, this.entryX + 4, this.entryY + 4, color, label);
        this.drawString(ctx, this.entryX + 4, this.entryY + 14, 0xFFAAAAAA,
                RecipeBrowseModel.partsLine(parts));

        // 子控件（删除按钮）永远最后画，否则会被文字盖住
        super.render(ctx, mouseX, mouseY, isSelected);
    }
}
