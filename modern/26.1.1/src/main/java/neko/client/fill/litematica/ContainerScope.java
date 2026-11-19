package neko.client.fill.litematica;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.materials.MaterialListBase;
import fi.dy.masa.litematica.schematic.LitematicaSchematic;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 「容器材料」要算哪一张原理图 —— 由<b>当前正在看的这份材料列表</b>决定。
 *
 * <h2>为什么需要它（真机踩过两次）</h2>
 * <p>最早的实现固定扫「世界里所有<b>启用</b>的投影」，于是不管从哪张图的材料列表点按钮，
 * 开出来的都是同一份东西（蜂箱农场点出来的是「164K抗卸载7gt刷沙机 等 3 个」）。
 * 只按「投影」反推之后又漏了第二条入口：F6 →「加载原理图」/「原理图管理器」进来的是
 * {@code MaterialListSchematic}，它<b>根本没有 placement</b>，于是又掉回「全部启用」——
 * 出来的还是同一张表。</p>
 *
 * <h2>两种材料列表，两条路</h2>
 * <ul>
 *   <li><b>投影列表</b>（{@code GuiSchematicPlacementsList}）→
 *       {@code MaterialListPlacement}：持有 {@code private final SchematicPlacement placement}；</li>
 *   <li><b>加载原理图 / 原理图管理器</b>（{@code GuiSchematicLoad$MaterialListCreator}）
 *       → {@code MaterialListSchematic}：持有
 *       {@code private final LitematicaSchematic schematic}，<b>跟有没有放置完全无关</b>。</li>
 * </ul>
 * <p>两者都<b>没有 getter</b>（五版本 javap 逐个核对过），所以按<b>字段类型</b>反射取
 * （{@link ReflectField}）：字段名会改，类型是 Litematica 的 public 类。</p>
 *
 * <h2>🔴 关键：扫描单元是「一张原理图」，不是「一个投影」</h2>
 * <p>把扫描单元写成 {@code SchematicPlacement} 是上一版的错误：没有 placement 的图无路可走，
 * 只能退回「全部启用」。现在单元是 {@link Target}（名字 + {@code LitematicaSchematic}），
 * placement 只是它的一种<b>来源</b> ⇒ 没放置的图照样算得出容器材料，
 * 这正是「加载原理图 → 材料列表」这条路的正确答案。</p>
 *
 * <p>也不按「同一张图的所有投影」合并：一次只看一张图，标题写这一张的名字，
 * 与 Litematica 自己的材料列表同粒度。</p>
 */
public final class ContainerScope {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    /**
     * 一次扫描的单元：<b>一张原理图</b> + 用来做标题的名字。
     *
     * @param name      标题里显示的名字（投影名或原理图元数据名），可为 {@code null}
     * @param schematic 要读容器 NBT 的那张图
     */
    public record Target(String name, LitematicaSchematic schematic) {
    }

    private ContainerScope() {
    }

    /**
     * 解析出这次要算哪几张图。
     *
     * <p>{@code source} 是打开界面前玩家正在看的那份材料列表，可以是 {@code null}
     * （热键从世界里直接打开，或当前界面不是材料列表）。永远不返回空 ——
     * 拿不到就退回「所有启用的投影」并打日志。</p>
     */
    public static List<Target> resolve(MaterialListBase source) {
        if (source instanceof ContainerFillMaterialList own) {
            List<Target> kept = own.scope();
            if (!kept.isEmpty()) {
                return kept;
            }
        }
        if (source == null) {
            return allEnabled();
        }
        // 投影列表那条路：直接就是「这一个投影的那张图」。
        SchematicPlacement placement = ReflectField.ofType(source, SchematicPlacement.class);
        if (placement != null && placement.getSchematic() != null) {
            return List.of(of(placement));
        }
        // 加载原理图 / 原理图管理器那条路：🔴 不需要它被放置。
        LitematicaSchematic schematic = ReflectField.ofType(source, LitematicaSchematic.class);
        if (schematic != null) {
            return List.of(of(schematic));
        }
        List<Target> all = allEnabled();
        LOGGER.info("[容器填充物] 当前材料列表（{}）对不上任何原理图，改算全部启用投影 {} 个",
                source.getClass().getSimpleName(), all.size());
        return all;
    }

    /** 一个投影 → 它的那张图（名字用投影名：玩家在投影列表里看到的就是它）。 */
    public static Target of(SchematicPlacement placement) {
        if (placement == null) {
            return new Target(null, null);
        }
        return new Target(placement.getName(), placement.getSchematic());
    }

    /** 一张图 → 它自己（名字用元数据名，与「加载原理图」列表里显示的一致）。 */
    public static Target of(LitematicaSchematic schematic) {
        return new Target(nameOf(schematic), schematic);
    }

    /** 世界里所有<b>启用</b>的投影（只作兜底：热键从世界里直接打开时用）。 */
    public static List<Target> allEnabled() {
        List<Target> targets = new ArrayList<>();
        for (SchematicPlacement placement : allPlacements()) {
            if (!placement.isEnabled() || placement.getSchematic() == null) {
                continue;
            }
            targets.add(of(placement));
        }
        return targets;
    }

    /** 世界里注册的全部投影（不看启用状态）；解析出的那一张不按启用状态过滤。 */
    static List<SchematicPlacement> allPlacements() {
        SchematicPlacementManager manager = DataManager.getSchematicPlacementManager();
        if (manager == null) {
            return List.of();
        }
        List<SchematicPlacement> placements = new ArrayList<>();
        for (SchematicPlacement placement : manager.getAllSchematicsPlacements()) {
            if (placement != null) {
                placements.add(placement);
            }
        }
        return placements;
    }

    /** 原理图的元数据名字（{@code MaterialListSchematic#getName()} 取的也是它）。 */
    static String nameOf(LitematicaSchematic schematic) {
        if (schematic == null || schematic.getMetadata() == null) {
            return null;
        }
        return schematic.getMetadata().getName();
    }
}
