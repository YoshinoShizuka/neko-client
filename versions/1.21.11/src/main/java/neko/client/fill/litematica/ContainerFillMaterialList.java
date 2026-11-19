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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
 * <h2>算哪一张原理图</h2>
 * <p>🔴 之前固定算「所有启用的投影」，于是从任何一张图的材料列表点按钮都开出同一份表
 * （真机踩过）。范围现在由打开时那份材料列表决定（{@link ContainerScope}）：</p>
 * <ul>
 *   <li><b>投影列表</b>（F6 → 已加载的投影 → 材料列表）⇒ 算<b>那个投影</b>的那张图；</li>
 *   <li><b>加载原理图 / 原理图管理器</b>（F6 → 加载原理图 → 材料列表）⇒ 算<b>那份原理图文件</b>
 *       本身 —— 它<b>可能还没被放置</b>，所以扫描单元是「一张图」而不是「一个投影」；</li>
 *   <li>拿不到对应关系（热键从世界里直接打开）⇒ 退回「所有启用的投影」，标题会写「等 N 个」。</li>
 * </ul>
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
    /** 这次算哪几张图（由入口那份材料列表定，见 {@link ContainerScope}）。 */
    private List<ContainerScope.Target> scope = List.of();
    /** 标题形态（原理图名字 + 容器材料），扫描时定下来。 */
    private ContainerTitle.Title title = ContainerTitle.of(List.of());

    public ContainerFillMaterialList() {
    }

    /**
     * 打开界面。没有投影时不拦着 —— 空列表本身就是答案「你没放投影」。
     *
     * <p>范围从<b>当前界面</b>的材料列表反推：热键在某个材料列表里按下时，
     * 算的也是那一张（与按钮一致）。</p>
     */
    public static void open(Minecraft client) {
        open(client, sourceOf(client));
    }

    /**
     * 打开界面，并指定「玩家刚才看的是哪份材料列表」。
     *
     * @param source 当前材料列表（可以是 {@code null} ⇒ 算所有启用的投影）
     */
    public static void open(Minecraft client, MaterialListBase source) {
        if (client == null || client.level == null) {
            return;
        }
        ensureTicker();
        ContainerFillMaterialList list = new ContainerFillMaterialList();
        list.scope = ContainerScope.resolve(source);
        list.reCreateMaterialList();
        current = list;
        takeOverDataManager(list);
        QuickClientScreenAccess.setScreen(client, new GuiMaterialList(list));
    }

    /** 当前界面若是材料列表，返回它那份材料列表；否则 {@code null}。 */
    private static MaterialListBase sourceOf(Minecraft client) {
        if (client == null) {
            return null;
        }
        return QuickClientScreenAccess.currentScreen(client) instanceof GuiMaterialList gui
                ? gui.getMaterialList() : null;
    }

    /** 这次扫描的范围（{@link ContainerScope} 会读它，避免从本界面再点按钮时范围被放大）。 */
    List<ContainerScope.Target> scope() {
        return this.scope;
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
        if (!tookOverDataManager) {
            tookOverDataManager = true;
            previousList = DataManager.getMaterialList();
        }
        // 🔴 已经接管过也要指到新表：从本界面再点一次按钮会开一份新表，
        //    只记「接管过」而不重指，DataManager 会一直停在上一份（已废弃的）表上；
        //    releaseDataManager 又因为它 != current 而不还原 ⇒ printer 永远问一张死表。
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
     * 从某个材料列表的按钮进来：算的就是<b>那一份</b>。
     *
     * <p>🔴 参数是必需项，没有无参重载：混入少传一个参数就只能拿「所有启用的投影」，
     * 于是所有入口开出同一张表（0.5.7 真机反馈的原症状）。故意不留无参版本，
     * 免得以后有人顺手调它又把范围放大。</p>
     */
    public static void openCurrent(MaterialListBase source) {
        open(Minecraft.getInstance(), source);
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
        rebuildInventoryCounts();
        if (this.scope.isEmpty()) {
            this.scope = ContainerScope.allEnabled();
        }
        QuickLitematicaContainerTotals.Scan scan = QuickLitematicaContainerTotals.scanScope(this.scope);
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
     * <p>每行的可用数量是<b>渲染时现算</b>的（{@link ContainerEntry#getCountAvailable()}，
     * 只查表），所以这里<b>不用</b>碰列表内容 —— 行会自己画出新数字。</p>
     *
     * <p>🔴 表头那行合计是 {@code GuiMaterialList.createCounts()} 在 {@code initGui()} 时
     * 「烤」进去的（读的是字段值），不重建就一直是开界面那一刻的数字。而
     * {@code onTaskCompleted()} 的实现就是 <b>{@code initGui()}</b> —— 整个控件树重建、
     * 滚动条回到顶部。所以只在<b>数字真的变了</b>时重建，且 1 秒内最多一次：
     * 否则仓库每 5 秒一刷，玩家正翻着列表就被拉回顶部，感觉整页都在卡。</p>
     */
    public void refreshAvailable() {
        this.builtAtVersion = WarehouseStock.snapshotVersion();
        if (!this.recountTotals()) {
            return;
        }
        Minecraft client = Minecraft.getInstance();
        long now = client == null || client.level == null ? 0L : client.level.getGameTime();
        if (lastHeaderTick != Long.MIN_VALUE && now - lastHeaderTick < HEADER_INTERVAL_TICKS) {
            return;
        }
        lastHeaderTick = now;
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
        long now = client.level == null ? 0L : client.level.getGameTime();
        boolean inventoryChanged = false;
        if (lastInventoryTick == Long.MIN_VALUE || now - lastInventoryTick >= INVENTORY_INTERVAL_TICKS) {
            lastInventoryTick = now;
            inventoryChanged = rebuildInventoryCounts();
        }
        if (inventoryChanged || list.builtAtVersion != WarehouseStock.snapshotVersion()) {
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
        this.recountTotals();
    }

    /**
     * 重算表头三个合计。
     *
     * @return 合计有没有变化（没变就不值得重建界面）
     */
    private boolean recountTotals() {
        long total = 0;
        long missing = 0;
        long mismatched = 0;
        for (MaterialListEntry entry : this.getMaterialsAll()) {
            if (entry == null) {
                continue;
            }
            total += entry.getCountTotal();
            missing += ContainerGap.trueGap(entry.getCountTotal(), entry.getCountAvailable());
            mismatched += entry.getCountMismatched();
        }
        boolean changed = total != this.countTotal
                || missing != this.countMissing
                || mismatched != this.countMismatched;
        this.countTotal = total;
        this.countMissing = missing;
        this.countMismatched = mismatched;
        return changed;
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

        /**
         * 「已有」= 背包 + 仓库。
         *
         * <p>🔴 两个来源都<b>只查表</b>：这个方法每帧、每行都会被 Litematica 调（画可用列、
         * 算缺失、算表头合计各一次）。旧实现在这里扫 36 格背包 + 反射读 printer 缓存，
         * 几百行 × 60 帧就是每秒几万次 —— 界面卡顿的主因。</p>
         */
        @Override
        public int getCountAvailable() {
            long warehouse = WarehouseStock.amountOf(this.itemId);
            int inventory = inventoryCount(this.getStack().getItem());
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

    /**
     * 背包计数缓存（物品 → 件数），前 36 格、与取货计数同口径。
     *
     * <p>每 {@value #INVENTORY_INTERVAL_TICKS} tick 整份重算一次，行渲染只查它 ——
     * 见 {@link ContainerEntry#getCountAvailable()}。</p>
     */
    private static Map<Item, Integer> inventoryCounts = Map.of();
    private static long lastInventoryTick = Long.MIN_VALUE;
    private static long lastHeaderTick = Long.MIN_VALUE;
    /** 表头重建最多 1 秒一次（它等于整棵树 {@code initGui()}，顺带把滚动条拉回顶部）。 */
    private static final int HEADER_INTERVAL_TICKS = 20;
    /** 背包变化不必每 tick 都重排列表；0.5 秒的误差看不出来。 */
    private static final int INVENTORY_INTERVAL_TICKS = 10;

    /** 缓存里这种物品有几件（不扫背包）。 */
    static int inventoryCount(Item item) {
        if (item == null) {
            return 0;
        }
        Integer count = inventoryCounts.get(item);
        return count == null ? 0 : count;
    }

    /**
     * 重算背包缓存。
     *
     * @return 是否发生了变化（变了才值得让列表重排 / 重算表头）
     */
    static boolean rebuildInventoryCounts() {
        Map<Item, Integer> next = countInventory(Minecraft.getInstance());
        if (next.equals(inventoryCounts)) {
            return false;
        }
        inventoryCounts = next;
        return true;
    }

    /** 一次遍历算出「背包里每种物品有几件」。 */
    static Map<Item, Integer> countInventory(Minecraft client) {
        if (client == null || client.player == null) {
            return Map.of();
        }
        var inventory = client.player.getInventory();
        Map<Item, Integer> counts = new HashMap<>();
        for (int slot = 0; slot < Math.min(36, inventory.getContainerSize()); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (!stack.isEmpty()) {
                counts.merge(stack.getItem(), stack.getCount(), Integer::sum);
            }
        }
        return Map.copyOf(counts);
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
