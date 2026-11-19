package neko.client.fill.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 整张原理图里<b>所有容器内填充物</b>的合计。
 *
 * <h2>为什么是「容器内填充物」而不是「方块统计」</h2>
 * <p>Litematica 自己的材料列表数的是<b>方块</b>：这座建筑用了 320 个箱子。那回答不了
 * 「这些箱子里该装什么」。真正决定要往仓库要什么货的，是每个容器方块 NBT 里
 * {@code Items} 写明的「第几格放什么、放几个」。本类只读后者。</p>
 *
 * <h2>数据从哪来</h2>
 * <p>不扫描世界、不读区块，直接读<b>已放置投影</b>的方块实体表：
 * {@code SchematicPlacement#getSchematic()} → 每个 region 的
 * {@code getBlockEntityMapForRegion}。所以投影没加载、没启用时这里是空的 ——
 * 那正是「没有可比对的东西」，不是错误。</p>
 *
 * <h2>不做什么</h2>
 * <ul>
 *   <li>不把潜影盒里的东西算成需求：盒子里装的是「盒子」这一件物品，
 *       展开会让「要 1 个装满石头的盒子」变成「要 27 组石头」，与填充行为不符。</li>
 *   <li>不做双箱配对：原版双箱只有一个方块实体持有 {@code Items}，另一半没有，
 *       逐条累加天然不会重复。</li>
 *   <li>不读实体容器（驴/羊驼身上的箱子）：那是实体不是方块，投影里也没有可靠的
 *       物品快照，先不算。</li>
 * </ul>
 */
public final class QuickLitematicaContainerTotals {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /**
     * 单次扫描最多处理多少个方块实体。
     *
     * <p>扫描发生在客户端线程（按下热键那一刻），几万个容器会明显卡顿。真到那个量级，
     * 先出一份「截断」的结果比让玩家以为游戏死了更好。</p>
     */
    private static final int MAX_BLOCK_ENTITIES = 40_000;

    /** 一种物品的合计需求：代表栈只用来取图标/名字，数量另算。 */
    public record Row(ItemStack stack, int count) {
    }

    /**
     * 一次扫描的结果。
     *
     * @param rows        按数量从多到少排好的合计表
     * @param placements  参与合计的投影个数
     * @param containers  真正带物品的容器个数
     * @param unreadable  有 {@code Items} 但整条读不出来的容器个数
     * @param truncated   是否撞上 {@link #MAX_BLOCK_ENTITIES} 提前收工
     */
    public record Scan(List<Row> rows, int placements, int containers,
                       int unreadable, boolean truncated) {
        public static final Scan EMPTY = new Scan(List.of(), 0, 0, 0, false);

        public boolean isEmpty() {
            return this.rows.isEmpty();
        }

        public int totalItems() {
            int total = 0;
            for (Row row : this.rows) {
                total += row.count();
            }
            return total;
        }
    }

    private QuickLitematicaContainerTotals() {
    }

    /** 扫描当前世界所有<b>启用</b>的投影。没有投影时返回 {@link Scan#EMPTY}。 */
    public static Scan scan() {
        Minecraft client = Minecraft.getInstance();
        if (client == null || client.level == null) {
            return Scan.EMPTY;
        }
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) {
            return Scan.EMPTY;
        }
        List<SchematicPlacement> placements = new ArrayList<>();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement == null || !placement.isEnabled()) {
                continue;
            }
            if (placement.getSchematic() == null) {
                continue;
            }
            placements.add(placement);
        }
        if (placements.isEmpty()) {
            return Scan.EMPTY;
        }
        long startedAt = System.nanoTime();
        Scan scan = scan(placements, client.level.registryAccess());
        LOGGER.info("[容器填充物] 合计 {} 种 / {} 件（投影 {} 个，容器 {} 个{}），{} ms",
                scan.rows().size(), scan.totalItems(), scan.placements(), scan.containers(),
                scan.truncated() ? "，已截断" : "", (System.nanoTime() - startedAt) / 1_000_000L);
        return scan;
    }

    /** 只扫给定的投影（测试/调试用；正式入口走 {@link #scan()}）。 */
    static Scan scan(List<SchematicPlacement> placements, HolderLookup.Provider lookup) {
        if (placements == null || placements.isEmpty() || lookup == null) {
            return Scan.EMPTY;
        }
        Map<String, Integer> counts = new LinkedHashMap<>();
        Map<String, ItemStack> samples = new LinkedHashMap<>();
        int containers = 0;
        int unreadable = 0;
        int visited = 0;
        boolean truncated = false;

        outer:
        for (SchematicPlacement placement : placements) {
            LitematicaSchematic schematic = placement.getSchematic();
            if (schematic == null) {
                continue;
            }
            Set<String> regions = schematic.getAreas() == null
                    ? Set.of() : schematic.getAreas().keySet();
            for (String region : regions) {
                Map<BlockPos, CompoundTag> blockEntities =
                        QuickLitematicaContainerMaterialsAccess.getBlockEntities(schematic, region);
                if (blockEntities == null) {
                    continue;
                }
                for (CompoundTag nbt : blockEntities.values()) {
                    if (visited++ >= MAX_BLOCK_ENTITIES) {
                        truncated = true;
                        break outer;
                    }
                    List<ItemStack> stacks =
                            QuickLitematicaContainerMaterials.readItemsForStatistics(nbt, lookup);
                    if (stacks == null) {
                        unreadable++;
                        continue;
                    }
                    boolean any = false;
                    for (ItemStack stack : stacks) {
                        String id = idOf(stack);
                        if (id == null) {
                            continue;
                        }
                        any = true;
                        addCount(counts, id, stack.getCount());
                        samples.putIfAbsent(id, oneOf(stack));
                    }
                    if (any) {
                        containers++;
                    }
                }
            }
        }

        return new Scan(toRows(counts, samples), placements.size(), containers, unreadable, truncated);
    }

    /**
     * 纯逻辑：把一批 id/数量并进合计表（同 id 累加）。
     *
     * <p>单独拆出来是为了能在 JUnit 里直接跑 —— 扫描那部分要 Litematica 和注册表，
     * 测不了；「同一个 id 在多格出现要不要相加」这种判据必须测。</p>
     */
    static void addCount(Map<String, Integer> counts, String id, int count) {
        if (counts == null || id == null || id.isEmpty() || count <= 0) {
            return;
        }
        counts.merge(id, count, Integer::sum);
    }

    /** 合计表 → 按数量从多到少（同数量按 id）排好的行；数量为 0 的不出行。 */
    static List<Row> toRows(Map<String, Integer> counts, Map<String, ItemStack> samples) {
        List<Row> rows = new ArrayList<>();
        if (counts == null || samples == null) {
            return rows;
        }
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            int count = entry.getValue() == null ? 0 : entry.getValue();
            ItemStack sample = samples.get(entry.getKey());
            if (count <= 0 || sample == null || sample.isEmpty()) {
                continue;
            }
            rows.add(new Row(sample, count));
        }
        rows.sort((left, right) -> {
            int byCount = Integer.compare(right.count(), left.count());
            return byCount != 0 ? byCount : left.stack().getItem().toString()
                    .compareTo(right.stack().getItem().toString());
        });
        return rows;
    }

    /** 物品 id（{@code namespace:path}）；空气/空栈返回 null。 */
    static String idOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || stack.getItem() == net.minecraft.world.item.Items.AIR) {
            return null;
        }
        Identifier key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? null : key.toString();
    }

    private static ItemStack oneOf(ItemStack stack) {
        ItemStack copy = stack.copy();
        copy.setCount(1);
        return copy;
    }
}
