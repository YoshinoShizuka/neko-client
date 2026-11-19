package neko.client.fill.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.gui.GuiMaterialList;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.materials.MaterialListEntry;
import fi.dy.masa.malilib.util.StringUtils;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import neko.client.WarehouseStock;
import neko.client.fill.QuickClientScreenAccess;

import java.util.ArrayList;
import java.util.List;

/**
 * 「容器填充物合计」材料列表：整张原理图所有容器里要求的物品，对照<b>背包 + 虚空仓库</b>。
 *
 * <h2>两个入口</h2>
 * <p>主入口是 Litematica 材料列表工具栏上的「容器材料」按钮
 * （{@code neko.client.mixin.GuiMaterialListButtonMixin}）；备用入口是
 * {@code openContainerMaterials} 热键（默认未绑定）。都不需要先右键某个容器、
 * 也不需要正在填充。列表就是 Litematica 自己的材料列表界面（{@link GuiMaterialList}），
 * 所以「需要 / 已有 / 缺失」三列、倍数、只显示缺失、排序全都是现成的 ——
 * 本类只负责把三列里的数字填对，外加把标题写成「原理图'名字'容器材料」。</p>
 *
 * <h2>三列各是什么</h2>
 * <ul>
 *   <li><b>需要</b>：{@link QuickLitematicaContainerTotals} 扫出来的容器内填充物合计
 *       （来自投影容器 NBT，不是方块统计）。</li>
 *   <li><b>已有</b>：你背包里的同种物品 + {@link WarehouseStock} 拿到的仓库库存。
 *       仓库那一半是<b>只读</b>的，不会扣库存。</li>
 *   <li><b>缺失</b>：需要 − 已有。仓库补不了的部分就是它。</li>
 * </ul>
 *
 * <h2>为什么要重扫 / 为什么不用重扫</h2>
 * <p>只有第一次打开时扫一遍投影（{@link #reCreateMaterialList()}）。仓库数字回执到了以后
 * 只重算可用数量（{@link #refreshAvailable()}）—— 需求那边跟仓库无关，重扫纯属浪费。</p>
 */
public final class ContainerFillMaterialList extends MaterialListBase {

    private static final String NAME_KEY = "neko_client.materials.name";

    /** 当前打开的那份列表；界面关掉就清空（tick 里判断 screen 是否还是它）。 */
    private static ContainerFillMaterialList current;
    /** 打开我们之前 DataManager 记着的材料列表；界面关掉时还回去。 */
    private static MaterialListBase previousList;
    private static boolean tookOverDataManager;
    private static boolean tickerReady;

    private long builtAtVersion;
    private QuickLitematicaContainerTotals.Scan lastScan = QuickLitematicaContainerTotals.Scan.EMPTY;
    /** 标题形态（原理图名字 + 容器材料），扫描时定下来。 */
    private ContainerTitle.Title title = ContainerTitle.of(List.of());

    public ContainerFillMaterialList() {
    }

    /** 打开界面。没有投影时不拦着 —— 空列表本身就是答案「你没放投影」。 */
    public static void open(Minecraft client) {
        if (client == null || client.level == null) {
            return;
        }
        ensureTicker();
        ContainerFillMaterialList list = new ContainerFillMaterialList();
        list.reCreateMaterialList();
        current = list;
        takeOverDataManager(list);
        QuickClientScreenAccess.setScreen(client, new GuiMaterialList(list));
    }

    /**
     * 把自己登记成 Litematica 的「当前材料列表」。
     *
     * <p>🔴 不登记就等于数字全是别人的。{@code GuiMaterialList} 的构造器只在
     * {@code DataManager.getMaterialList() == null} 时才登记；玩家先前看过原版材料列表之后，
     * 那里永远停着<b>那一份</b>。而装了 litematica-printer 时，仓库数字正是它按
     * 「当前材料列表里的物品」去服务端问来的 ⇒ 我们只能读到上一份清单的库存，
     * 本表里不在那份清单中的物品一律是 0（真机实证：两边「白色染色玻璃」都是 998941，
     * 而箭 / 紫颂花 / 铁桶 / 骨头 全 0 —— 它们不是方块材料）。</p>
     *
     * <p>登记之后 printer 会按<b>本表</b>的物品清单去问，本表才有正确数字；
     * 关闭界面时必须还回去（{@link #releaseDataManager()}），否则它会把我们的清单
     * 当成玩家的材料列表一直问下去。</p>
     */
    private static void takeOverDataManager(ContainerFillMaterialList list) {
        if (tookOverDataManager) {
            return;
        }
        tookOverDataManager = true;
        previousList = DataManager.getMaterialList();
        DataManager.setMaterialList(list);
    }

    /** 把「当前材料列表」还给打开本界面之前那一份（没有就还空）。 */
    private static void releaseDataManager() {
        if (!tookOverDataManager) {
            return;
        }
        tookOverDataManager = false;
        if (DataManager.getMaterialList() == current) {
            DataManager.setMaterialList(previousList);
        }
        previousList = null;
    }

    /**
     * 从界面按钮进来：按钮回调里没有 client，自己取。
     *
     * <p>给材料列表工具栏那个「容器材料」混入用（
     * {@code neko.client.mixin.GuiMaterialListButtonMixin}）—— 混入只依赖本方法，
     * 不碰 {@code Minecraft} 之外任何会跨版本改名的 API。</p>
     */
    public static void openCurrent() {
        open(Minecraft.getInstance());
    }

    @Override
    public String getName() {
        return StringUtils.translate(NAME_KEY);
    }

    @Override
    public String getTitle() {
        return StringUtils.translate(this.title.key(), this.title.args());
    }

    /** 重扫投影的容器 NBT，重建整张表（打开界面时调一次）。 */
    @Override
    public void reCreateMaterialList() {
        QuickLitematicaContainerTotals.Scan scan = QuickLitematicaContainerTotals.scan();
        List<MaterialListEntry> entries = new ArrayList<>(scan.rows().size());
        List<String> ids = new ArrayList<>(scan.rows().size());
        for (QuickLitematicaContainerTotals.Row row : scan.rows()) {
            String id = QuickLitematicaContainerTotals.idOf(row.stack());
            if (id == null) {
                continue;
            }
            ids.add(id);
            entries.add(new ContainerEntry(row.stack(), row.count(), id));
        }
        this.lastScan = scan;
        this.title = ContainerTitle.of(scan.names());
        this.builtAtVersion = WarehouseStock.snapshotVersion();
        this.setMaterialListEntries(entries);
        // 登记需求 ⇒ 仓库快照自己会去拉；回执到了 tick 里只重算「已有」。
        // 先清旧的：换了一张投影之后，上一份清单的物品不该继续被问（也会让快照里留垃圾键）。
        WarehouseStock.forgetDemand();
        WarehouseStock.noteDemand(ids);
    }

    /**
     * 只重算「已有 / 缺失」，不重扫投影。
     *
     * <p>每行的可用数量是渲染时现算的（{@link ContainerEntry#getCountAvailable()}），
     * 所以这里只要把表头和过滤列表刷新一下。</p>
     */
    public void refreshAvailable() {
        this.builtAtVersion = WarehouseStock.snapshotVersion();
        this.updateCounts();
        this.refreshPreFilteredList();
        if (this.completionListener != null) {
            this.completionListener.onTaskCompleted();
        }
    }

    /** 最近一次扫描的统计（给日志/调试看）。 */
    public QuickLitematicaContainerTotals.Scan lastScan() {
        return this.lastScan;
    }

    private static void ensureTicker() {
        if (tickerReady) {
            return;
        }
        tickerReady = true;
        ClientTickEvents.END_CLIENT_TICK.register(ContainerFillMaterialList::tick);
    }

    /** 仓库快照变了就把「已有」刷新一次；界面关掉就停。 */
    private static void tick(Minecraft client) {
        ContainerFillMaterialList list = current;
        if (list == null) {
            return;
        }
        if (!(QuickClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui)
                || gui.getMaterialList() != list) {
            releaseDataManager();
            current = null;
            return;
        }
        if (list.builtAtVersion != WarehouseStock.snapshotVersion()) {
            list.refreshAvailable();
        }
    }

    /**
     * 表头合计：按「需求 − 已有」现算。
     *
     * <p>🔴 不能沿用父类的「把每行 {@code getCountMissing()} 加起来」——
     * 装了 litematica-printer 时那个值给的是 {@code total}（见
     * {@link WarehouseStock#rowsRenderedByPrinter()}），照加会把表头算成「缺失 = 100%」。
     * 这里直接按需求减已有，两种渲染路径都对得上。</p>
     */
    @Override
    public void updateCounts() {
        this.countTotal = 0;
        this.countMissing = 0;
        this.countMismatched = 0;
        for (MaterialListEntry entry : this.getMaterialsAll()) {
            if (entry == null) {
                continue;
            }
            this.countTotal += entry.getCountTotal();
            this.countMissing += ContainerGap.trueGap(entry.getCountTotal(), entry.getCountAvailable());
            this.countMismatched += entry.getCountMismatched();
        }
    }

    /**
     * 一行「容器填充物」。
     *
     * <p>可用数量<b>必须现算</b>：仓库快照是异步来的，构造时算死就永远是 0 或旧值。
     * 缺失同样现算 —— {@code MaterialListBase.updateCounts()} 走的是虚方法，
     * 表头合计才会跟着一起对。</p>
     */
    public static final class ContainerEntry extends MaterialListEntry {

        private final String itemId;

        private ContainerEntry(ItemStack stack, int total, String itemId) {
            super(stack, total, total, 0, 0);
            this.itemId = itemId;
        }

        @Override
        public int getCountAvailable() {
            long warehouse = WarehouseStock.amountOf(this.itemId);
            int inventory = countInInventory(Minecraft.getInstance(), this.getStack().getItem());
            long sum = inventory + Math.max(0L, warehouse);
            return sum > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) sum;
        }

        /**
         * 「缺失」列。
         *
         * <p>两种渲染路径要的值不一样（真机踩过）：</p>
         * <ul>
         *   <li><b>原版渲染</b>：直接画这个数 ⇒ 必须是「需求 − 已有」。</li>
         *   <li><b>litematica-printer 渲染</b>：它把这个数当成「还没到位的量」，
         *       自己再减一次已有 ⇒ 必须给 {@code total}，它减完才正好是「需求 − 已有」。
         *       给「需求 − 已有」会变成 {@code total - 2 × available}，仓库只补到一半的行
         *       会少报一半缺口。</li>
         * </ul>
         */
        @Override
        public int getCountMissing() {
            return ContainerGap.missingFor(this.getCountTotal(), this.getCountAvailable(),
                    WarehouseStock.rowsRenderedByPrinter());
        }
    }

    /** 背包（前 36 格，与取货计数同口径）里这种物品有多少件。 */
    static int countInInventory(Minecraft client, Item item) {
        if (client == null || client.player == null || item == null) {
            return 0;
        }
        var inventory = client.player.getInventory();
        int count = 0;
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty() && stack.getItem() == item) {
                count += stack.getCount();
            }
        }
        return count;
    }
}
