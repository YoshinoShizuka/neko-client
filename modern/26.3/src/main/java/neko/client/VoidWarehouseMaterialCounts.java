package neko.client;

/**
 * 「材料已有数量」的纯计算：背包数量 + 虚空仓库数量。
 *
 * <p>真正接进 Litematica 材料列表的只有 {@code MaterialListEntry#getCountAvailable}
 * 一处，其余都是边界处理：负数、溢出、仓库没数据。放在没有 Fabric / MC 依赖的
 * 静态方法里，边界能被 JUnit 直接咬住，注入点只负责「拿返回值、算一下、塞回去」。</p>
 *
 * <p><b>只影响「已有数量」</b>：本功能是只读统计，不会改动仓库库存，也不会把材料
 * 取到手上，所以 Litematica 的「缺失数量」仍按实际手上的缺口计算。</p>
 */
public final class VoidWarehouseMaterialCounts {

    private VoidWarehouseMaterialCounts() {
    }

    /**
     * 把仓库数量并入已有数量。
     *
     * @param inventoryCount  原版算出来的已有数量（玩家背包，含嵌套潜影盒）
     * @param warehouseAmount 虚空仓库里的数量；没有数据时传 0
     * @return 相加后的数量，饱和在 {@link Integer#MAX_VALUE}
     */
    public static int merge(int inventoryCount, long warehouseAmount) {
        // 界面上这个数只被当「够不够」用，负数没有意义。原版不会给负数，
        // 但仓库那边是 long、还来自网络，多一道夹紧不亏。
        long base = Math.max(0, inventoryCount);
        if (warehouseAmount <= 0L) {
            return (int) Math.min(base, Integer.MAX_VALUE);
        }
        // 仓库可能已经是 Long.MAX_VALUE，先判断饱和，避免相加后 long 也溢出。
        if (warehouseAmount >= (long) Integer.MAX_VALUE - base) {
            return Integer.MAX_VALUE;
        }
        return (int) (base + warehouseAmount);
    }
}
