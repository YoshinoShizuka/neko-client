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
 * 「容器材料」要扫哪几个投影 —— 由<b>当前正在看的这份材料列表</b>决定。
 *
 * <h2>为什么需要它（真机踩过）</h2>
 * <p>之前的实现固定扫「世界里所有<b>启用</b>的投影」，于是不管从哪张原理图的材料列表点按钮，
 * 开出来的都是同一份东西：玩家在蜂箱农场的材料列表里点「容器材料」，
 * 弹出的标题却是「原理图'164K抗卸载7gt刷沙机' 等 3 个 · 容器材料」。
 * 按钮的意义是「看这一张图的容器里要装什么」，范围必须跟着入口走。</p>
 *
 * <h2>怎么从材料列表反推投影</h2>
 * <p>Litematica 有两种材料列表，都<b>没有</b>公开的「我对应哪个投影」的方法
 * （五版本 javap 逐个核对过）：</p>
 * <ul>
 *   <li>{@code MaterialListPlacement}（从投影列表点「材料列表」进来，最常见）：
 *       持有 {@code private final SchematicPlacement placement}，无 getter；</li>
 *   <li>{@code MaterialListSchematic}（从原理图管理器点「材料列表」进来）：
 *       持有 {@code private final LitematicaSchematic schematic}，无 getter。</li>
 * </ul>
 * <p>所以按<b>字段类型</b>反射取（{@link #fieldOfType}）：字段名不予依赖（Litematica 改个名字
 * 我们不会跟着挂），类型是 Litematica 自己的 public 类，稳定得多。取到之后：</p>
 * <ol>
 *   <li>拿到 {@code SchematicPlacement} ⇒ 就扫它一个（子区块启停状态、朝向、镜像都是这一份的）；</li>
 *   <li>只拿到 {@code LitematicaSchematic} ⇒ 扫「同一张图」的所有投影（先按对象身份，
 *       再按原理图名字兜底）；</li>
 *   <li>都拿不到（区域选择的材料列表、别人的实现）⇒ 退回「所有启用的投影」——
 *       也就是原来的行为，标题会写「等 N 个」，玩家至少能看出范围被放大了。</li>
 * </ol>
 *
 * <h2>为什么不改用「身份比对」反查</h2>
 * <p>{@code SchematicPlacement#getMaterialList()} 是懒加载 + 缓存，理论上可以遍历所有投影
 * 比引用。但那会给<b>每一个</b>投影都造一份材料列表（只为比对一次），而反射是只读的。
 * 顺序上反射在前，正是为了避开这种副作用。</p>
 */
public final class ContainerScope {

    private static final Logger LOGGER = LoggerFactory.getLogger("neko-client");

    private ContainerScope() {
    }

    /**
     * 解析出这次要扫的投影。
     *
     * <p>{@code source} 是打开界面前玩家正在看的那份材料列表，可以是 {@code null}
     * （热键从世界里直接打开，或当前界面不是材料列表）。永远不返回 {@code null}，
     * 也永远不返回空 —— 拿不到就退回「所有启用的投影」。</p>
     */
    public static List<SchematicPlacement> resolve(MaterialListBase source) {
        if (source instanceof ContainerFillMaterialList own) {
            List<SchematicPlacement> kept = own.scope();
            if (!kept.isEmpty()) {
                return kept;
            }
        }
        if (source == null) {
            return allEnabled();
        }
        SchematicPlacement placement = fieldOfType(source, SchematicPlacement.class);
        if (placement != null && placement.getSchematic() != null) {
            return List.of(placement);
        }
        LitematicaSchematic schematic = fieldOfType(source, LitematicaSchematic.class);
        if (schematic != null) {
            List<SchematicPlacement> same = sameSchematic(schematic);
            if (!same.isEmpty()) {
                return same;
            }
        }
        List<SchematicPlacement> all = allEnabled();
        LOGGER.info("[容器填充物] 当前材料列表（{}）对不上任何投影，改扫全部启用投影 {} 个",
                source.getClass().getSimpleName(), all.size());
        return all;
    }

    /** 世界里所有<b>启用</b>的投影（原来的范围，现在只作兜底）。 */
    public static List<SchematicPlacement> allEnabled() {
        List<SchematicPlacement> placements = new ArrayList<>();
        for (SchematicPlacement placement : allPlacements()) {
            if (placement == null || !placement.isEnabled() || placement.getSchematic() == null) {
                continue;
            }
            placements.add(placement);
        }
        return placements;
    }

    /**
     * 世界里注册的<b>全部</b>投影（不看启用状态）。
     *
     * <p>解析出的那一个不按启用状态过滤：玩家特意打开了这张图的材料列表，
     * 投影暂时关着（在盖别的东西）也一样要看它的料单。</p>
     */
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

    /**
     * 同一张原理图的所有投影：先按对象身份（同一个 {@code LitematicaSchematic} 实例），
     * 再按原理图名字兜底（同一份文件被各自加载成两个实例时）。
     */
    static List<SchematicPlacement> sameSchematic(LitematicaSchematic target) {
        List<SchematicPlacement> all = allPlacements();
        List<SchematicPlacement> byIdentity = new ArrayList<>();
        for (SchematicPlacement placement : all) {
            if (placement.getSchematic() == target) {
                byIdentity.add(placement);
            }
        }
        if (!byIdentity.isEmpty()) {
            return byIdentity;
        }
        String name = nameOf(target);
        if (name == null || name.isEmpty()) {
            return List.of();
        }
        List<SchematicPlacement> byName = new ArrayList<>();
        for (SchematicPlacement placement : all) {
            if (name.equals(nameOf(placement.getSchematic()))) {
                byName.add(placement);
            }
        }
        return byName;
    }

    /** 原理图的元数据名字（{@code MaterialListSchematic#getName()} 取的也是它）。 */
    static String nameOf(LitematicaSchematic schematic) {
        if (schematic == null || schematic.getMetadata() == null) {
            return null;
        }
        return schematic.getMetadata().getName();
    }

    /**
     * 按<b>类型</b>从对象（及其父类）里读一个字段 —— 不依赖字段名。
     *
     * <p>实现见 {@link ReflectField#ofType}：那条路径是这次修复的关键（读不到就会退回
     * 「扫全部投影」，也就是原来的 bug），所以它被抽成零依赖的纯工具类并单独单测。</p>
     */
    static <T> T fieldOfType(Object owner, Class<T> type) {
        return ReflectField.ofType(owner, type);
    }
}
