package neko.client.fill.litematica;

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
 * <h2>这是一个真入口</h2>
 * <p>按热键（默认未绑定）随时打开，不需要先右键某个容器、也不需要先缺料。列表就是
 * Litematica 自己的材料列表界面（{@link GuiMaterialList}），所以「需要 / 已有 / 缺失」
 * 三列、倍数、只显示缺失、排序全都是现成的 —— 本类只负责把三列里的数字填对。</p>
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
    private static final String TITLE_KEY = "neko_client.materials.title";

    /** 当前打开的那份列表；界面关掉就清空（tick 里判断 screen 是否还是它）。 */
    private static ContainerFillMaterialList current;
    private static boolean tickerReady;

    private long builtAtVersion;
    private QuickLitematicaContainerTotals.Scan lastScan = QuickLitematicaContainerTotals.Scan.EMPTY;

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
        QuickClientScreenAccess.setScreen(client, new GuiMaterialList(list));
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
        return StringUtils.translate(TITLE_KEY);
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
        this.builtAtVersion = WarehouseStock.snapshotVersion();
        this.setMaterialListEntries(entries);
        // 登记需求 ⇒ 仓库快照自己会去拉；回执到了 tick 里只重算「已有」。
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
            current = null;
            return;
        }
        if (list.builtAtVersion != WarehouseStock.snapshotVersion()) {
            list.refreshAvailable();
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

        @Override
        public int getCountMissing() {
            return Math.max(0, this.getCountTotal() - this.getCountAvailable());
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
