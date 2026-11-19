package neko.client.fill.litematica;

/**
 * 「缺失」这一列到底该给渲染层什么数（纯逻辑，可单测）。
 *
 * <h2>为什么需要这么个类</h2>
 * <p>这张表有两个渲染者，它们对 {@code getCountMissing()} 的<b>语义假设不同</b>：</p>
 * <ul>
 *   <li><b>原版 Litematica</b>：直接把返回值画进「缺失」列 ⇒ 我们要给「需求 − 已有」。</li>
 *   <li><b>litematica-printer</b>：它给 {@code WidgetMaterialListEntry.render} 加了 Redirect，
 *       认为 missing 和 available 是<b>两个独立的量</b>（原版语义：missing = 还没建成的方块数，
 *       available = 手上的货），于是画 {@code max(0, missing − available)}。
 *       我们要是给「需求 − 已有」，它减完就是 {@code 需求 − 2 × 已有} ——
 *       仓库只补到一半的行会少报一半缺口（真机踩到过）。</li>
 * </ul>
 *
 * <p>所以在 printer 渲染时返回 {@code total}，让它那一步减法落在正确的起点上；
 * 两条路最后算出来的缺口都等于 {@link #trueGap}。这条等式由单测钉住。</p>
 */
public final class ContainerGap {

    private ContainerGap() {
    }

    /** 真正的缺口：需求 − 已有，不小于 0。表头合计也用它。 */
    public static int trueGap(int total, int available) {
        return Math.max(0, total - available);
    }

    /**
     * 交给渲染层的「缺失」值。
     *
     * @param printerRenders litematica-printer 是否正在画这一行（它会自己再减一次已有）
     */
    public static int missingFor(int total, int available, boolean printerRenders) {
        if (printerRenders) {
            return Math.max(0, total);
        }
        return trueGap(total, available);
    }

    /**
     * 复刻 printer 那条路的算式（{@code netGap}），只为把「两条路缺口一致」写成断言。
     *
     * <p>生产代码不调它 —— 真正画的时候是 printer 自己在算。</p>
     */
    public static int printerGap(int missing, int available) {
        return Math.max(0, missing - available);
    }
}
